package com.sd.demo.singlecache

import androidx.test.ext.junit.runners.AndroidJUnit4
import app.cash.turbine.test
import com.sd.lib.singlecache.SingleCacheEntity
import com.sd.lib.singlecache.SingleCache
import com.sd.lib.singlecache.singleCache
import com.squareup.moshi.JsonDataException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.ContinuationInterceptor

@RunWith(AndroidJUnit4::class)
class SingleCacheTest {
  @Test
  fun testUpdateAndGet() = runBlocking {
    val cache = singleCache { TestUpdateModel() }
    assertEquals(true, cache.update { null })
    assertEquals(TestUpdateModel(), cache.get())

    assertEquals(true, cache.update { it.copy(name = "update") })
    assertEquals(TestUpdateModel(name = "update"), cache.get())

    // 返回null删除缓存，还原为默认值
    assertEquals(true, cache.update { null })
    assertEquals(TestUpdateModel(), cache.get())
  }

  /** 每次返回新实例并调用一次getDefault，同一类型的实例互相能读到对方的更新 */
  @Test
  fun testNewInstance() = runBlocking {
    val count = AtomicInteger()
    val cache1 = singleCache { count.incrementAndGet(); TestInstanceModel() }
    val cache2 = singleCache { count.incrementAndGet(); TestInstanceModel() }
    assertNotSame(cache1, cache2)
    assertEquals(2, count.get())

    assertEquals(true, cache1.update { it.copy(name = "update") })
    assertEquals(TestInstanceModel(name = "update"), cache2.get())
  }

  @Test
  fun testFlow() = runBlocking {
    val cache = singleCache { TestFlowModel() }
    assertEquals(true, cache.update { null })

    cache.flow.test(timeout = TEST_TIMEOUT) {
      assertEquals(TestFlowModel(), awaitItem())

      assertEquals(true, cache.update { it.copy(name = "update") })
      assertEquals(TestFlowModel(name = "update"), awaitItem())

      assertEquals(true, cache.update { null })
      assertEquals(TestFlowModel(), awaitItem())
    }
  }

  @Test
  fun testMultipleSubscribers() = runBlocking<Unit> {
    val cache = singleCache { TestMultiSubscriberModel() }
    assertEquals(true, cache.update { null })

    val expect = TestMultiSubscriberModel(name = "update")
    val subscribers = List(3) {
      async(Dispatchers.Default) {
        cache.flow.test(timeout = TEST_TIMEOUT) { awaitItemUntil(expect) }
      }
    }
    assertEquals(true, cache.update { expect })
    subscribers.awaitAll()
  }

  /** 并发更新不能丢失修改 */
  @Test
  fun testConcurrentUpdate() = runBlocking {
    val cache = singleCache { TestCounterModel() }
    assertEquals(true, cache.update { null })

    List(100) {
      async(Dispatchers.Default) { cache.update { it.copy(count = it.count + 1) } }
    }.awaitAll().forEach { assertEquals(true, it) }
    assertEquals(TestCounterModel(count = 100), cache.get())
  }

  /** 快速连续更新时，订阅者最终收到最新值 */
  @Test
  fun testRapidUpdate() = runBlocking {
    val cache = singleCache { TestRapidModel() }
    assertEquals(true, cache.update { null })

    cache.flow.test(timeout = TEST_TIMEOUT) {
      assertEquals(TestRapidModel(), awaitItem())
      val job = launch(Dispatchers.Default) {
        repeat(50) { cache.update { it.copy(count = it.count + 1) } }
      }
      awaitItemUntil(TestRapidModel(count = 50))
      job.join()
    }
  }

  /** 解码失败视为无缓存，update可以覆盖 */
  @Test
  fun testDecodeFailure() = runBlocking {
    // 在首次访问前写入无法解码的内容
    writeCacheFileDirectly(DECODE_FAILURE_MODEL_ID, "not json")
    CacheErrors.clear()

    val cache = singleCache { TestDecodeFailureModel() }
    assertEquals(TestDecodeFailureModel(), cache.get())
    assertTrue(CacheErrors.list().isNotEmpty())

    assertEquals(true, cache.update { it.copy(name = "fixed") })
    assertEquals(TestDecodeFailureModel(name = "fixed"), cache.get())
  }

  /** JSON字符串内包含非法UTF-8字节时视为解码失败，不能读出包含替换字符的值 */
  @Test
  fun testInvalidUtf8() = runBlocking {
    val invalidBytes = """{"name":"""".toByteArray() + byteArrayOf(0xFF.toByte()) + """"}""".toByteArray()
    writeCacheFileDirectly(INVALID_UTF8_MODEL_ID, invalidBytes)
    CacheErrors.clear()

    val cache = singleCache { TestInvalidUtf8Model() }
    assertEquals(TestInvalidUtf8Model(), cache.get())
    assertTrue(CacheErrors.list().any { it is CharacterCodingException })
  }

  /** 无法解码的缓存可以删除 */
  @Test
  fun testDeleteUndecodable() = runBlocking {
    assertDeleteUndecodable(
      id = DELETE_UNDECODABLE_MODEL_ID,
      cache = singleCache { TestDeleteUndecodableModel() },
      defaultValue = TestDeleteUndecodableModel(),
    )
  }

  /** 可空字段和Map中的null写入后原样读回 */
  @Test
  fun testNullRoundTrip() = runBlocking {
    assertNullRoundTrip(
      cache = singleCache { TestNullValueModel() },
      value = TestNullValueModel(name = null, tags = mapOf("key" to null)),
    )
  }

  /** 不可空字段读到null时整个缓存解码失败，视为无缓存 */
  @Test
  fun testNullForNonNullField() = runBlocking {
    writeCacheFileDirectly(NON_NULL_FIELD_MODEL_ID, """{"name":null,"count":2}""")
    CacheErrors.clear()

    val cache = singleCache { TestNonNullFieldModel() }
    assertEquals(TestNonNullFieldModel(), cache.get())
    assertTrue(CacheErrors.list().any { it is JsonDataException })
  }

  /** 替换数据文件失败时，内存中不能留下没有落盘的值 */
  @Test
  fun testReplaceFailureNotCached() = runBlocking {
    assertReplaceFailureNotCached(
      id = REPLACE_FAILURE_MODEL_ID,
      cache = singleCache { TestReplaceFailureModel() },
      value = TestReplaceFailureModel(name = "old"),
      newValue = TestReplaceFailureModel(name = "new"),
    )
  }

  /** 非法UTF-16写入失败，已有缓存不变 */
  @Test
  fun testInvalidUtf16() = runBlocking {
    val cache = singleCache { TestUtf16Model() }
    assertEquals(true, cache.update { it.copy(name = "valid") })
    CacheErrors.clear()

    assertEquals(false, cache.update { it.copy(name = "\uD800") })
    assertTrue(CacheErrors.list().any { it is JsonDataException && it.message.orEmpty().contains("Invalid UTF-16") })
    assertEquals(TestUtf16Model(name = "valid"), cache.get())
  }

  /** block抛出的异常原样抛给调用方，不经过异常处理器 */
  @Test
  fun testBlockException() {
    val cache = singleCache { TestBlockExceptionModel() }
    CacheErrors.clear()
    assertThrows(TestBlockException::class.java) {
      runBlocking { cache.update { throw TestBlockException() } }
    }
    assertTrue(CacheErrors.list().none { it is TestBlockException })
  }

  /** block是挂起函数，在IO上执行 */
  @Test
  fun testSuspendBlock() = runBlocking {
    val cache = singleCache { TestSuspendBlockModel() }
    var blockDispatcher: ContinuationInterceptor? = null
    val result = cache.update {
      delay(10)
      blockDispatcher = currentCoroutineContext()[ContinuationInterceptor]
      it.copy(name = "suspend")
    }
    assertEquals(true, result)
    assertEquals(Dispatchers.IO, blockDispatcher)
    assertEquals(TestSuspendBlockModel(name = "suspend"), cache.get())
  }

  /** 调用方的线程被runBlocking阻塞时，同步更新不能死锁 */
  @Test
  fun testNoDeadlockUpdatingOnBlockedThread() = runBlocking {
    assertNoDeadlockUpdatingOnBlockedThread(singleCache { TestBlockedUpdateModel() }) { it.copy(count = it.count + 1) }
  }

  /** 调用方的线程被runBlocking阻塞时，同步读取不能死锁 */
  @Test
  fun testNoDeadlockReadingOnBlockedThread() = runBlocking {
    assertNoDeadlockReadingOnBlockedThread(singleCache { TestBlockedReadModel() })
  }

  /** 读取失败后、开始监听文件前发生的写入不能漏掉 */
  @Test
  fun testWriteRightAfterReadFailure() = runBlocking {
    assertReceiveWriteRightAfterReadFailure(
      id = READ_RECOVER_MODEL_ID,
      cache = singleCache { TestReadRecoverModel() },
      defaultValue = TestReadRecoverModel(),
      newValue = TestReadRecoverModel(name = "recovered"),
      newContent = """{"name":"recovered"}""",
    )
  }

  /** 恢复读取时锁被占用，订阅者不能反复重读并上报异常 */
  @Test
  fun testNoBusyRetryWhileLocked() = runBlocking {
    assertNoBusyRetryWhileLocked(
      id = LOCKED_RECOVER_MODEL_ID,
      cache = singleCache { TestLockedRecoverModel() },
      value = TestLockedRecoverModel(name = "disk"),
      content = """{"name":"disk"}""",
      updatedValue = TestLockedRecoverModel(name = "updated"),
    )
  }

  /** 多个订阅者同时取消后，订阅计数不能丢失 */
  @Test
  fun testCollectorCounterAfterConcurrentCancel() = runBlocking {
    assertCollectorCounterAfterConcurrentCancel(singleCache { TestCollectorCountModel() })
  }

  /** 没有订阅者时读取不启动文件监听 */
  @Test
  fun testGetNotObservingFile() = runBlocking {
    assertGetNotObservingFile(singleCache { TestGetObserverModel() })
  }

  /** 订阅期间拿锁失败时异常交给ExceptionHandler，进程不能崩溃，之后继续监听 */
  @Test
  fun testLockFailureReported() = runBlocking {
    assertLockFailureReported(
      id = LOCK_FAILURE_MODEL_ID,
      cache = singleCache { TestLockFailureModel() },
      value = TestLockFailureModel(name = "value"),
      newValue = TestLockFailureModel(name = "new"),
      newContent = """{"name":"new"}""",
    )
  }

  /** 数据目录创建失败后恢复，订阅者能收到之后的写入 */
  @Test
  fun testRecoverAfterDirectoryFailure() = runBlocking {
    assertRecoverAfterDirectoryFailure(
      id = DIRECTORY_FAILURE_MODEL_ID,
      cache = singleCache { TestDirectoryFailureModel() },
      defaultValue = TestDirectoryFailureModel(),
      updatedValue = TestDirectoryFailureModel(name = "updated"),
      externalValue = TestDirectoryFailureModel(name = "external"),
      externalContent = """{"name":"external"}""",
    )
  }

  /** 计算缓存文件路径失败时Flow不结束，恢复后能收到新值 */
  @Test
  fun testRecoverAfterProduceFileFailure() = runBlocking {
    assertRecoverAfterProduceFileFailure(
      id = PRODUCE_FILE_FAILURE_MODEL_ID,
      clazz = TestProduceFileFailureModel::class.java,
      memoryCache = true,
      defaultValue = TestProduceFileFailureModel(),
      updatedValue = TestProduceFileFailureModel(name = "updated"),
    )
  }

  /** 读取失败时Flow不结束，首次失败发射默认值，文件恢复后能收到新值 */
  @Test
  fun testReadFailure() = runBlocking {
    val file = singleCacheFile(READ_FAILURE_MODEL_ID)
    writeCacheFileDirectly(READ_FAILURE_MODEL_ID, """{"name":"disk"}""")
    CacheErrors.clear()

    val cache = singleCache { TestReadFailureModel() }
    cache.flow.test(timeout = TEST_TIMEOUT) {
      withoutReadPermission(file) {
        assertEquals(TestReadFailureModel(), awaitItem())
      }
      assertTrue(CacheErrors.list().isNotEmpty())

      // 订阅者可能已恢复读取，这时只有版本号变化才会重读，所以通过缓存写入
      assertEquals(true, cache.update { it.copy(name = "recovered") })
      awaitItemUntil(TestReadFailureModel(name = "recovered"))
    }
  }

  @Test
  fun testDuplicateId() {
    SingleCache.get(TestDuplicateIdModelA::class.java) { TestDuplicateIdModelA() }
    val error = assertThrows(IllegalArgumentException::class.java) {
      SingleCache.get(TestDuplicateIdModelB::class.java) { TestDuplicateIdModelB() }
    }
    assertTrue(error.message.orEmpty().contains("has bound to"))
  }

  @Test
  fun testMissingAnnotation() {
    assertThrows(IllegalArgumentException::class.java) {
      SingleCache.get(TestNoAnnotationModel::class.java) { TestNoAnnotationModel() }
    }
  }

  @Test
  fun testGenericEntity() {
    val error = assertThrows(IllegalArgumentException::class.java) {
      singleCache { TestGenericModel("default") }
    }
    assertTrue(error.message.orEmpty().contains("Type parameters"))
  }
}

private const val DECODE_FAILURE_MODEL_ID = "TestDecodeFailureModel"
private const val INVALID_UTF8_MODEL_ID = "TestInvalidUtf8Model"
private const val DELETE_UNDECODABLE_MODEL_ID = "TestDeleteUndecodableModel"
private const val REPLACE_FAILURE_MODEL_ID = "TestReplaceFailureModel"
private const val NON_NULL_FIELD_MODEL_ID = "TestNonNullFieldModel"
private const val READ_FAILURE_MODEL_ID = "TestReadFailureModel"
private const val READ_RECOVER_MODEL_ID = "TestReadRecoverModel"
private const val LOCKED_RECOVER_MODEL_ID = "TestLockedRecoverModel"
private const val DIRECTORY_FAILURE_MODEL_ID = "TestDirectoryFailureModel"
private const val PRODUCE_FILE_FAILURE_MODEL_ID = "TestProduceFileFailureModel"
private const val LOCK_FAILURE_MODEL_ID = "TestLockFailureModel"
private const val DUPLICATE_MODEL_ID = "TestDuplicateIdModel"

@SingleCacheEntity("TestUpdateModel")
data class TestUpdateModel(val name: String = "default")

@SingleCacheEntity("TestInstanceModel")
data class TestInstanceModel(val name: String = "default")

@SingleCacheEntity("TestFlowModel")
data class TestFlowModel(val name: String = "default")

@SingleCacheEntity("TestMultiSubscriberModel")
data class TestMultiSubscriberModel(val name: String = "default")

@SingleCacheEntity("TestCounterModel")
data class TestCounterModel(val count: Int = 0)

@SingleCacheEntity("TestRapidModel")
data class TestRapidModel(val count: Int = 0)

@SingleCacheEntity(DECODE_FAILURE_MODEL_ID)
data class TestDecodeFailureModel(val name: String = "default")

@SingleCacheEntity(INVALID_UTF8_MODEL_ID)
data class TestInvalidUtf8Model(val name: String = "default")

@SingleCacheEntity(DELETE_UNDECODABLE_MODEL_ID)
data class TestDeleteUndecodableModel(val name: String = "default")

@SingleCacheEntity(REPLACE_FAILURE_MODEL_ID)
data class TestReplaceFailureModel(val name: String = "default")

@SingleCacheEntity("TestNullValueModel")
data class TestNullValueModel(
  val name: String? = "default",
  val tags: Map<String, String?> = mapOf("key" to "default"),
)

@SingleCacheEntity(NON_NULL_FIELD_MODEL_ID)
data class TestNonNullFieldModel(val name: String = "default", val count: Int = 1)

@SingleCacheEntity("TestUtf16Model")
data class TestUtf16Model(val name: String = "default")

@SingleCacheEntity("TestBlockExceptionModel")
data class TestBlockExceptionModel(val name: String = "default")

@SingleCacheEntity("TestSuspendBlockModel")
data class TestSuspendBlockModel(val name: String = "default")

@SingleCacheEntity("TestBlockedUpdateModel")
data class TestBlockedUpdateModel(val count: Int = 0)

@SingleCacheEntity("TestBlockedReadModel")
data class TestBlockedReadModel(val name: String = "default")

@SingleCacheEntity(READ_FAILURE_MODEL_ID)
data class TestReadFailureModel(val name: String = "default")

@SingleCacheEntity(READ_RECOVER_MODEL_ID)
data class TestReadRecoverModel(val name: String = "default")

@SingleCacheEntity(LOCKED_RECOVER_MODEL_ID)
data class TestLockedRecoverModel(val name: String = "default")

@SingleCacheEntity(DIRECTORY_FAILURE_MODEL_ID)
data class TestDirectoryFailureModel(val name: String = "default")

@SingleCacheEntity(PRODUCE_FILE_FAILURE_MODEL_ID)
data class TestProduceFileFailureModel(val name: String = "default")

@SingleCacheEntity(DUPLICATE_MODEL_ID)
data class TestDuplicateIdModelA(val name: String = "default")

@SingleCacheEntity(DUPLICATE_MODEL_ID)
data class TestDuplicateIdModelB(val name: String = "default")

data class TestNoAnnotationModel(val name: String = "default")

@SingleCacheEntity("TestGenericModel")
data class TestGenericModel<T>(val value: T)

private class TestBlockException : RuntimeException()

@SingleCacheEntity("TestCollectorCountModel")
data class TestCollectorCountModel(val name: String = "default")

@SingleCacheEntity(LOCK_FAILURE_MODEL_ID)
data class TestLockFailureModel(val name: String = "default")

@SingleCacheEntity("TestGetObserverModel")
data class TestGetObserverModel(val name: String = "default")
