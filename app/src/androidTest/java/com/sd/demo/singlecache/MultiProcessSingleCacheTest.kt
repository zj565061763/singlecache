package com.sd.demo.singlecache

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.cash.turbine.test
import com.sd.lib.singlecache.SingleCache
import com.sd.lib.singlecache.singleCache
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

@RunWith(AndroidJUnit4::class)
class MultiProcessSingleCacheTest {
  private val _context = InstrumentationRegistry.getInstrumentation().targetContext
  private val _connection = RemoteServiceConnection()
  private val _cache = singleCache { MultiProcessCounterModel() }
  private val _diskCache = singleCache(memoryCache = false) { MultiProcessDiskCounterModel() }

  @Before
  fun setUp() {
    val intent = Intent(_context, MultiProcessTestService::class.java)
    assertEquals(true, _context.bindService(intent, _connection, Context.BIND_AUTO_CREATE))
  }

  @After
  fun tearDown() {
    _context.unbindService(_connection)
  }

  /** 两个进程同时对同一个值加一，不能丢失修改 */
  @Test
  fun testConcurrentUpdate() = runBlocking {
    testConcurrentUpdate(_cache, diskModel = false)
  }

  /** 本进程用仅磁盘缓存时，两个进程同时加一也不能丢失修改 */
  @Test
  fun testConcurrentUpdateDisk() = runBlocking {
    testConcurrentUpdate(_diskCache, diskModel = true)
  }

  private suspend fun <T : MultiProcessCounter<T>> testConcurrentUpdate(cache: SingleCache<T>, diskModel: Boolean) = coroutineScope {
    assertEquals(true, cache.update { null })
    val startAt = SystemClock.elapsedRealtime() + START_DELAY_MILLIS

    val remote = async {
      request(timeout = 60.seconds) { MultiProcessTestService.newIncrementMessage(diskModel = diskModel, times = TIMES, startAt = startAt, replyTo = it) }
    }
    val local = async(Dispatchers.IO) {
      waitUntil(startAt)
      var success = 0
      repeat(TIMES) {
        if (cache.update { it.withCount(it.count + 1) }) success++
      }
      success
    }

    assertEquals(TIMES, local.await())
    assertEquals(TIMES, remote.await())
    assertEquals(TIMES * 2, cache.get().count)
    assertEquals(TIMES * 2, request { MultiProcessTestService.newGetMessage(diskModel = diskModel, replyTo = it) })
  }

  /** 其他进程写入后，本进程的订阅者能收到新值 */
  @Test
  fun testRemoteUpdateNotifiesFlow() = runBlocking {
    assertEquals(true, _cache.update { null })
    _cache.flow.test(timeout = TEST_TIMEOUT) {
      assertEquals(MultiProcessCounterModel(), awaitItem())
      assertEquals(1, request { MultiProcessTestService.newSetMessage(diskModel = false, value = 12345, replyTo = it) })
      awaitItemUntil(MultiProcessCounterModel(count = 12345))
    }
  }

  /** 其他进程写入后，本进程仅磁盘缓存的订阅者能收到新值 */
  @Test
  fun testRemoteUpdateNotifiesDiskFlow() = runBlocking {
    assertEquals(true, _diskCache.update { null })
    _diskCache.flow.test(timeout = TEST_TIMEOUT) {
      assertEquals(MultiProcessDiskCounterModel(), awaitItem())
      assertEquals(1, request { MultiProcessTestService.newSetMessage(diskModel = true, value = 54321, replyTo = it) })
      awaitItemUntil(MultiProcessDiskCounterModel(count = 54321))
    }
  }

  /** 没有订阅者时，其他进程写入后，本进程读取也能拿到新值，依赖共享版本号判断内存值已过期 */
  @Test
  fun testRemoteUpdateWithoutSubscriber() = runBlocking {
    assertEquals(true, _cache.update { it.copy(count = 1) })
    assertEquals(1, _cache.get().count)
    assertEquals(1, request { MultiProcessTestService.newSetMessage(diskModel = false, value = 2, replyTo = it) })
    assertEquals(2, _cache.get().count)
  }

  /** 向远端进程发送[newMessage]创建的命令，返回回复中的arg1 */
  private suspend fun request(
    timeout: Duration = TEST_TIMEOUT,
    newMessage: (replyTo: Messenger) -> Message,
  ): Int {
    val remote = withTimeout(TEST_TIMEOUT) { _connection.awaitMessenger() }
    val result = CompletableDeferred<Int>()
    val replyTo = Messenger(Handler(Looper.getMainLooper()) { message ->
      result.complete(message.arg1)
      true
    })
    remote.send(newMessage(replyTo))
    return withTimeout(timeout) { result.await() }
  }
}

private class RemoteServiceConnection : ServiceConnection {
  private val _messenger = CompletableDeferred<Messenger>()

  override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
    if (service != null) _messenger.complete(Messenger(service))
  }

  override fun onServiceDisconnected(name: ComponentName?) {
    if (!_messenger.isCompleted) {
      _messenger.completeExceptionally(IllegalStateException("测试服务连接已断开"))
    }
  }

  suspend fun awaitMessenger(): Messenger = _messenger.await()
}

private fun waitUntil(startAt: Long) {
  while (true) {
    val remaining = startAt - SystemClock.elapsedRealtime()
    if (remaining <= 0) return
    Thread.sleep(remaining.coerceAtMost(10))
  }
}

private const val TIMES = 100
private const val START_DELAY_MILLIS = 500L
