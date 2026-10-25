package com.sd.demo.singlecache

import android.system.Os
import android.system.OsConstants
import androidx.test.platform.app.InstrumentationRegistry
import app.cash.turbine.ReceiveTurbine
import app.cash.turbine.test
import app.cash.turbine.withTurbineTimeout
import com.sd.lib.singlecache.SingleCache
import com.squareup.moshi.JsonEncodingException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import java.io.File
import java.io.FileNotFoundException
import java.io.RandomAccessFile
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.time.Duration.Companion.seconds

/**
 * 测试统一使用[kotlinx.coroutines.runBlocking]而不是`runTest`，等的都是真实时间：
 * [android.os.FileObserver]的事件投递、[kotlinx.coroutines.Dispatchers.IO]上的文件读写，
 * 都不受测试调度器控制，虚拟时间既跳不过它们，还会让`withTimeout`和`delay`失去意义。
 * 所以这里的超时也要显式给足，[app.cash.turbine.test]的默认超时只有1秒。
 */
val TEST_TIMEOUT = 15.seconds

/** [id]对应的缓存文件，与`SingleCache`的规则保持一致 */
fun singleCacheFile(id: String): File {
  val context = InstrumentationRegistry.getInstrumentation().targetContext
  return context.filesDir.resolve("sd.lib.singlecache").resolve("${md5(id)}.json")
}

/**
 * 绕过DataStore直接写缓存文件，模拟外部写入。
 * 先写临时文件再重命名，触发MOVED_TO事件。
 */
fun writeCacheFileDirectly(id: String, content: String) {
  writeCacheFileDirectly(id, content.toByteArray())
}

/**
 * 绕过DataStore直接写缓存文件，模拟外部写入。
 * 先写临时文件再重命名，触发MOVED_TO事件。
 */
fun writeCacheFileDirectly(id: String, bytes: ByteArray) {
  val file = singleCacheFile(id)
  file.parentFile?.mkdirs()
  val tempFile = file.resolveSibling("${file.name}.direct-${System.nanoTime()}")
  tempFile.writeBytes(bytes)
  assertEquals(true, tempFile.renameTo(file))
}

/**
 * 在[block]执行期间去掉[file]的读权限，模拟读取失败。
 * 只保留写权限，重命名覆盖仍能成功。
 */
inline fun <R> withoutReadPermission(file: File, block: () -> R): R {
  Os.chmod(file.absolutePath, OsConstants.S_IWUSR)
  try {
    return block()
  } finally {
    Os.chmod(file.absolutePath, OsConstants.S_IRUSR or OsConstants.S_IWUSR)
  }
}

/**
 * 读取失败上报异常时立即写入[newContent]，这时订阅者还没开始监听文件。
 * 订阅者先收到[defaultValue]，然后要能收到这次写入的[newValue]。
 */
suspend fun <T> assertReceiveWriteRightAfterReadFailure(
  id: String,
  cache: SingleCache<T>,
  defaultValue: T,
  newValue: T,
  newContent: String,
) {
  writeCacheFileDirectly(id, "{}")
  withoutReadPermission(singleCacheFile(id)) {
    CacheErrors.onAdd = {
      CacheErrors.onAdd = null
      // 重命名后是新文件，读权限随之恢复
      writeCacheFileDirectly(id, newContent)
    }
    try {
      cache.flow.test(timeout = TEST_TIMEOUT) {
        assertEquals(defaultValue, awaitItem())
        assertEquals(newValue, awaitItem())
      }
    } finally {
      CacheErrors.onAdd = null
    }
  }
}

/**
 * 数据目录创建失败时订阅者先收到[defaultValue]，目录恢复后要能收到[updatedValue]，
 * 之后文件监听仍然有效，能收到外部写入的[externalValue]。
 *
 * 会清空数据目录，用普通文件占住目录的路径来模拟创建失败。
 */
suspend fun <T> assertRecoverAfterDirectoryFailure(
  id: String,
  cache: SingleCache<T>,
  defaultValue: T,
  updatedValue: T,
  externalValue: T,
  externalContent: String,
) {
  val file = singleCacheFile(id)
  val directory = file.parentFile!!
  assertTrue(directory.deleteRecursively())
  assertTrue(directory.createNewFile())
  CacheErrors.clear()
  try {
    cache.flow.test(timeout = TEST_TIMEOUT) {
      assertEquals(defaultValue, awaitItem())
      assertTrue(CacheErrors.list().isNotEmpty())

      assertTrue(directory.delete())
      assertEquals(true, cache.update { updatedValue })
      // 要等库里10秒一次的重读，默认超时的余量太小
      withTurbineTimeout(30.seconds) { awaitItemUntil(updatedValue) }

      // 版本号变化后才会重读，先写入再给版本号加一，重读时文件已经是新内容
      writeCacheFileDirectly(id, externalContent)
      incrementVersion(file)
      // 再写入一次，保证版本号变化后有事件触发重读
      writeCacheFileDirectly(id, externalContent)
      awaitItemUntil(externalValue)
    }
  } finally {
    if (directory.isFile) directory.delete()
  }
}

/**
 * 计算缓存文件路径失败时（如API 23及以下filesDir返回null），订阅者先收到[defaultValue]，不能收到异常，
 * 恢复后要能收到[updatedValue]。
 *
 * 全局配置的数据目录计算过一次就不再变化，所以通过反射创建缓存，用会失败的produceFile模拟。
 */
suspend fun <T> assertRecoverAfterProduceFileFailure(
  id: String,
  clazz: Class<T>,
  memoryCache: Boolean,
  defaultValue: T,
  updatedValue: T,
) {
  val file = singleCacheFile(id)
  val failing = AtomicBoolean(true)
  val cache = newSingleCache(clazz, memoryCache, defaultValue) {
    if (failing.get()) throw TestProduceFileException()
    file
  }
  CacheErrors.clear()
  cache.flow.test(timeout = TEST_TIMEOUT) {
    assertEquals(defaultValue, awaitItem())
    assertTrue(CacheErrors.list().any { it is TestProduceFileException })

    failing.set(false)
    assertEquals(true, cache.update { updatedValue })
    // 要等库里10秒一次的重读，默认超时的余量太小
    withTurbineTimeout(30.seconds) { awaitItemUntil(updatedValue) }
  }
}

/**
 * 读取失败后，恢复读取时锁被[SingleCache.update]占用，订阅者不能反复重读并上报异常。
 * 这时DataStore会先发射不加锁读到的值，再抛出上次缓存的异常。
 */
suspend fun <T> assertNoBusyRetryWhileLocked(
  id: String,
  cache: SingleCache<T>,
  value: T,
  content: String,
  updatedValue: T,
) = coroutineScope {
  val file = singleCacheFile(id)
  writeCacheFileDirectly(id, content)
  val latch = CountDownLatch(1)
  try {
    cache.flow.test(timeout = TEST_TIMEOUT) {
      assertEquals(value, awaitItem())

      // 先换成不可读的文件再给版本号加一，版本号变化后的每次重读都会失败，DataStore缓存这次异常
      CacheErrors.clear()
      replaceWithUnreadableFile(file, content)
      incrementVersion(file)
      // 再替换一次，保证版本号变化后有事件触发重读
      replaceWithUnreadableFile(file, content)
      // 首次失败和注册监听后的重读各上报一次，稍等让剩余的事件处理完
      withTimeout(TEST_TIMEOUT) { while (CacheErrors.list().size < 2) delay(10) }
      delay(300)
      // 修改权限不会产生MOVED_TO事件
      Os.chmod(file.absolutePath, OsConstants.S_IRUSR or OsConstants.S_IWUSR)

      val blockStarted = CompletableDeferred<Unit>()
      // 在外层启动，断言失败时turbine只取消订阅，finally才能释放latch
      val updateJob = this@coroutineScope.launch(Dispatchers.IO) {
        cache.update {
          blockStarted.complete(Unit)
          latch.await()
          updatedValue
        }
      }
      blockStarted.await()

      // update持有锁期间触发重读
      val errorCount = CacheErrors.list().size
      writeCacheFileDirectly(id, content)
      delay(500)
      val repeatedErrors = CacheErrors.list().size - errorCount
      assertTrue("repeatedErrors:$repeatedErrors", repeatedErrors <= 2)

      latch.countDown()
      updateJob.join()
      awaitItemUntil(updatedValue)
    }
  } finally {
    latch.countDown()
  }
}

/**
 * 调用方的线程上还有进行中的[SingleCache.update]时，在这个线程上用runBlocking同步更新和读取不能死锁。
 * 用单线程调度器模拟主线程。
 */
suspend fun <T> assertNoDeadlockUpdatingOnBlockedThread(cache: SingleCache<T>, transform: (T) -> T) {
  withSingleThread {
    // 立即进入DataStore的队列，如果block要回到这个线程执行就会死锁
    val job = launch(start = CoroutineStart.UNDISPATCHED) { assertEquals(true, cache.update { transform(it) }) }
    runBlocking {
      withTimeout(TEST_TIMEOUT) {
        assertEquals(true, cache.update { transform(it) })
        cache.get()
      }
    }
    job.join()
  }
}

/**
 * 调用方的线程上有协程在等缓存内部的锁时，在这个线程上用runBlocking同步读取不能死锁。
 * 用单线程调度器模拟主线程，通过反射占住SingleCacheStore的锁。
 * 释放后锁会交给最先等待的协程，如果它在调用方的线程上等锁，就要回到这个线程才能继续，和runBlocking互相等待。
 */
suspend fun <T> assertNoDeadlockReadingOnBlockedThread(cache: SingleCache<T>) {
  val mutex = cache.storeMutex()
  assertTrue(mutex.tryLock())
  thread {
    Thread.sleep(500)
    mutex.unlock()
  }
  withSingleThread {
    val job = launch { cache.get() }
    // 让出线程，等先读取的协程开始等锁
    delay(100)
    runBlocking { withTimeout(TEST_TIMEOUT) { cache.get() } }
    job.join()
  }
}

/**
 * 多个订阅者同时取消后，DataStore的订阅计数不能丢失，否则文件监听不释放或不启动。
 * 订阅期间并发取flow的首个值，每轮结束后只剩这一个订阅者。
 * 不能用get()代替，它不计入订阅计数。
 */
suspend fun <T> assertCollectorCounterAfterConcurrentCancel(cache: SingleCache<T>) = coroutineScope {
  cache.flow.test(timeout = TEST_TIMEOUT) {
    awaitItem()
    // 收到首个值之后才计数
    withTimeout(TEST_TIMEOUT) { while (cache.collectorCounter() != 1) delay(10) }
    repeat(20) { round ->
      List(16) {
        async(Dispatchers.IO) { repeat(20) { cache.flow.first() } }
      }.awaitAll()
      assertEquals("round:$round", 1, cache.collectorCounter())
    }
    cancelAndIgnoreRemainingEvents()
  }
  // 没有长期订阅者时，文件监听会反复注册和释放
  repeat(20) {
    List(16) {
      async(Dispatchers.IO) { repeat(20) { cache.flow.first() } }
    }.awaitAll()
  }
  // 订阅全部结束后文件监听要全部释放，监听是进程级的，其他测试遗留的订阅也会导致失败
  val released = withTimeoutOrNull(TEST_TIMEOUT) { while (fileObserverCount() != 0) delay(10) } != null
  assertTrue("fileObservers:${fileObserverCount()} collectorCounter:${cache.collectorCounter()}", released)
}

/**
 * 没有订阅者时读取不能启动DataStore的文件监听，否则每次读取都要注册和释放监听。
 * 并发读取期间反复检查订阅计数和监听数量。
 */
suspend fun <T> assertGetNotObservingFile(cache: SingleCache<T>) = coroutineScope {
  // 监听是进程级的，先等其他测试遗留的监听释放
  withTimeout(TEST_TIMEOUT) { while (fileObserverCount() != 0) delay(10) }
  val reads = List(16) {
    async(Dispatchers.IO) { repeat(20) { cache.get() } }
  }
  while (reads.any { it.isActive }) {
    assertEquals(0, cache.collectorCounter() ?: 0)
    assertEquals(0, fileObserverCount())
    delay(1)
  }
}

/**
 * 订阅期间收到文件变化后拿锁失败，异常要交给ExceptionHandler，进程不能崩溃。
 * 恢复后要继续监听，订阅者能收到外部写入的[newValue]。
 * 把锁文件改成只读来模拟拿锁失败。
 */
suspend fun <T> assertLockFailureReported(
  id: String,
  cache: SingleCache<T>,
  value: T,
  newValue: T,
  newContent: String,
) {
  val file = singleCacheFile(id)
  val lockFile = File(file.absolutePath + ".lock")
  assertEquals(true, cache.update { value })
  try {
    cache.flow.test(timeout = TEST_TIMEOUT) {
      assertEquals(value, awaitItem())
      CacheErrors.clear()
      Os.chmod(lockFile.absolutePath, OsConstants.S_IRUSR)
      // 版本号变化后，文件事件或注册监听后的首个事件都会触发加锁重读
      incrementVersion(file)
      writeCacheFileDirectly(id, file.readText())
      withTimeout(TEST_TIMEOUT) { while (CacheErrors.list().none { it is FileNotFoundException }) delay(10) }

      Os.chmod(lockFile.absolutePath, OsConstants.S_IRUSR or OsConstants.S_IWUSR)
      // 积压的文件事件可能在写入期间触发重读，读到旧内容和新版本号后不再重读；
      // 所以写入期间占住DataStore的进程内锁，先给版本号加一，写入的事件一定触发重读。
      cache.coordinatorMutex().withLock {
        incrementVersion(file)
        writeCacheFileDirectly(id, newContent)
      }
      awaitItemUntil(newValue)
      cancelAndIgnoreRemainingEvents()
    }
  } finally {
    Os.chmod(lockFile.absolutePath, OsConstants.S_IRUSR or OsConstants.S_IWUSR)
  }
}

/**
 * 无法解码的缓存用`update { null }`删除后要清空文件，否则之后每次读盘都会上报解码异常。
 * 在首次读取前写入，保证读到的是无法解码的内容。
 */
suspend fun <T> assertDeleteUndecodable(id: String, cache: SingleCache<T>, defaultValue: T) {
  val file = singleCacheFile(id)
  writeCacheFileDirectly(id, "not json")
  CacheErrors.clear()
  assertEquals(defaultValue, cache.get())
  assertTrue(CacheErrors.list().any { it is JsonEncodingException })

  assertEquals(true, cache.update { null })
  assertEquals("", file.readText())
  CacheErrors.clear()
  assertEquals(defaultValue, cache.get())
  assertTrue(CacheErrors.list().none { it is JsonEncodingException })
}

/**
 * [value]中的null写入后要原样读回。
 * 再次update时block收到的是重新读盘的值，内存缓存也能检查磁盘上的内容。
 */
suspend fun <T> assertNullRoundTrip(cache: SingleCache<T>, value: T) {
  assertEquals(true, cache.update { value })
  var diskValue: Any? = null
  assertEquals(true, cache.update { it.also { diskValue = it } })
  assertEquals(value, diskValue)
  assertEquals(value, cache.get())
}

/**
 * 替换数据文件失败时update返回false，内存中不能留下没有落盘的值。
 * 在block里把数据文件换成目录让替换失败，再绕过DataStore写回原内容，读取时要重新读盘拿到原值。
 * 保持订阅，仅磁盘缓存才不会关闭DataStore。
 */
suspend fun <T> assertReplaceFailureNotCached(id: String, cache: SingleCache<T>, value: T, newValue: T) {
  val file = singleCacheFile(id)
  assertEquals(true, cache.update { value })
  val content = file.readText()
  try {
    cache.flow.test(timeout = TEST_TIMEOUT) {
      assertEquals(value, awaitItem())
      val result = cache.update {
        assertTrue(file.delete())
        assertTrue(file.mkdir())
        newValue
      }
      assertEquals(false, result)

      // 删除目录到写回之间文件不存在，这时加锁重读会把默认值连同最新版本号存进内存，之后读取不再读盘；
      // 所以写回期间占住DataStore的进程内锁。
      cache.coordinatorMutex().withLock {
        assertTrue(file.delete())
        writeCacheFileDirectly(id, content)
      }
      assertEquals(value, cache.get())
      cancelAndIgnoreRemainingEvents()
    }
  } finally {
    if (file.isDirectory) file.delete()
  }
}

/** 在单线程调度器上执行[block]，模拟主线程 */
private suspend fun withSingleThread(block: suspend CoroutineScope.() -> Unit) {
  val executor = Executors.newSingleThreadExecutor()
  try {
    withContext(executor.asCoroutineDispatcher(), block)
  } finally {
    executor.shutdown()
  }
}

/** 通过反射创建缓存，用[produceFile]计算缓存文件，与库中的构造参数保持一致 */
private fun <T> newSingleCache(clazz: Class<T>, memoryCache: Boolean, defaultValue: T, produceFile: () -> File): SingleCache<T> {
  val storeClass = Class.forName("com.sd.lib.singlecache.SingleCacheStore")
  val store = storeClass.getDeclaredConstructor(Class::class.java, Function0::class.java).newInstance(clazz, produceFile)
  if (memoryCache) storeClass.getDeclaredMethod("retainInMemory").invoke(store)
  @Suppress("UNCHECKED_CAST")
  return Class.forName("com.sd.lib.singlecache.SingleCacheImpl")
    .getDeclaredConstructor(storeClass, Any::class.java)
    .newInstance(store, defaultValue) as SingleCache<T>
}

private class TestProduceFileException : RuntimeException()

/** 通过反射取得SingleCacheStore的锁，与库中的字段名保持一致 */
private fun SingleCache<*>.storeMutex(): Mutex {
  val store = javaClass.getDeclaredField("store").apply { isAccessible = true }.get(this)!!
  return store.javaClass.getDeclaredField("_mutex").apply { isAccessible = true }.get(store) as Mutex
}

/** 通过反射取得DataStore加锁用的进程内锁，与库中的字段名保持一致 */
private fun SingleCache<*>.coordinatorMutex(): Mutex {
  val store = javaClass.getDeclaredField("store").apply { isAccessible = true }.get(this)!!
  val dataStore = store.javaClass.getDeclaredField("_dataStore").apply { isAccessible = true }.get(store)!!
  val coordinator = (dataStore.javaClass.getDeclaredField("coordinator\$delegate").apply { isAccessible = true }.get(dataStore) as Lazy<*>).value!!
  return coordinator.javaClass.getDeclaredField("inMemoryMutex").apply { isAccessible = true }.get(coordinator) as Mutex
}

/** 通过反射取得DataStore的订阅计数，DataStore已关闭时返回null，与库中的字段名保持一致 */
private fun SingleCache<*>.collectorCounter(): Int? {
  val store = javaClass.getDeclaredField("store").apply { isAccessible = true }.get(this)!!
  val dataStore = store.javaClass.getDeclaredField("_dataStore").apply { isAccessible = true }.get(store) ?: return null
  return dataStore.javaClass.getDeclaredField("collectorCounter").apply { isAccessible = true }.getInt(dataStore)
}

/** 通过反射取得已注册的文件监听数量，与库中的字段名保持一致 */
private fun fileObserverCount(): Int {
  val clazz = Class.forName("com.sd.lib.singlecache.datastore.MulticastFileObserver")
  val observers = clazz.getDeclaredField("fileObservers").apply { isAccessible = true }.get(null) as Map<*, *>
  return synchronized(clazz.getDeclaredField("LOCK").apply { isAccessible = true }.get(null)!!) { observers.size }
}

/** 直接给[file]的版本号加一，与DataStore的SharedCounter格式保持一致 */
private fun incrementVersion(file: File) {
  RandomAccessFile(File(file.absolutePath + ".version"), "rw").use { raf ->
    val buffer = raf.channel.map(FileChannel.MapMode.READ_WRITE, 0, Int.SIZE_BYTES.toLong())
    buffer.order(ByteOrder.nativeOrder())
    buffer.putInt(0, buffer.getInt(0) + 1)
  }
}

/** 用只有写权限的新文件替换[file]，触发MOVED_TO事件 */
private fun replaceWithUnreadableFile(file: File, content: String) {
  val tempFile = file.resolveSibling("${file.name}.unreadable-${System.nanoTime()}")
  tempFile.writeBytes(content.toByteArray())
  Os.chmod(tempFile.absolutePath, OsConstants.S_IWUSR)
  assertEquals(true, tempFile.renameTo(file))
}

/** 一直读取直到收到[expect]，收不到则由[ReceiveTurbine]的超时机制让测试失败 */
suspend fun <T> ReceiveTurbine<T>.awaitItemUntil(expect: T): T {
  while (true) {
    val item = awaitItem()
    if (item == expect) return item
  }
}

/** 与`com.sd.lib.singlecache.md5()`的实现保持一致 */
private fun md5(input: String): String {
  val md5Bytes = MessageDigest.getInstance("MD5").digest(input.toByteArray())
  return buildString {
    for (byte in md5Bytes) {
      val hex = (0xff and byte.toInt()).toString(16)
      if (hex.length == 1) append("0")
      append(hex)
    }
  }
}
