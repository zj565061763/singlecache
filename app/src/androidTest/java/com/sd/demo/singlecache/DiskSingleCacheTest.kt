package com.sd.demo.singlecache

import androidx.test.ext.junit.runners.AndroidJUnit4
import app.cash.turbine.test
import com.sd.lib.singlecache.SingleCacheEntity
import com.sd.lib.singlecache.singleCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicInteger

/** memoryCache=false的仅磁盘缓存 */
@RunWith(AndroidJUnit4::class)
class DiskSingleCacheTest {
  /** 每次返回新实例，并调用一次getDefault */
  @Test
  fun testNewInstance() {
    val count = AtomicInteger()
    val cache1 = singleCache(memoryCache = false) { count.incrementAndGet(); TestDiskInstanceModel() }
    val cache2 = singleCache(memoryCache = false) { count.incrementAndGet(); TestDiskInstanceModel() }
    assertNotSame(cache1, cache2)
    assertEquals(2, count.get())
  }

  /** 每个实例使用自己的默认值 */
  @Test
  fun testDefaultPerInstance() = runBlocking {
    val cache1 = singleCache(memoryCache = false) { TestDiskDefaultModel(name = "default1") }
    val cache2 = singleCache(memoryCache = false) { TestDiskDefaultModel(name = "default2") }
    assertEquals(true, cache1.update { null })
    assertEquals(TestDiskDefaultModel(name = "default1"), cache1.get())
    assertEquals(TestDiskDefaultModel(name = "default2"), cache2.get())
  }

  /** 没有使用者时不保留内存值，下次读取重新读盘 */
  @Test
  fun testReadFromDisk() = runBlocking {
    val cache = singleCache(memoryCache = false) { TestDiskReadModel() }
    assertEquals(true, cache.update { it.copy(name = "update") })
    assertEquals(TestDiskReadModel(name = "update"), cache.get())

    // 绕过DataStore写入，不会增加版本号，只有重新读盘才能读到
    writeCacheFileDirectly(DISK_READ_MODEL_ID, """{"name":"direct"}""")
    assertEquals(TestDiskReadModel(name = "direct"), cache.get())
  }

  /** 订阅者能收到其他实例的更新 */
  @Test
  fun testFlow() = runBlocking {
    val cache = singleCache(memoryCache = false) { TestDiskFlowModel() }
    assertEquals(true, cache.update { null })

    cache.flow.test(timeout = TEST_TIMEOUT) {
      assertEquals(TestDiskFlowModel(), awaitItem())

      val other = singleCache(memoryCache = false) { TestDiskFlowModel() }
      assertEquals(true, other.update { it.copy(name = "update") })
      assertEquals(TestDiskFlowModel(name = "update"), awaitItem())

      assertEquals(true, other.update { null })
      assertEquals(TestDiskFlowModel(), awaitItem())
    }
  }

  /** 多个实例并发更新不能丢失修改，期间DataStore会反复关闭和重新创建 */
  @Test
  fun testConcurrentUpdate() = runBlocking {
    assertEquals(true, singleCache(memoryCache = false) { TestDiskCounterModel() }.update { null })

    List(100) {
      async(Dispatchers.Default) {
        singleCache(memoryCache = false) { TestDiskCounterModel() }.update { it.copy(count = it.count + 1) }
      }
    }.awaitAll().forEach { assertEquals(true, it) }
    assertEquals(TestDiskCounterModel(count = 100), singleCache(memoryCache = false) { TestDiskCounterModel() }.get())
  }

  /** 同一类型的两种模式共用一个DataStore，互相能读到对方的更新 */
  @Test
  fun testMixedMode() = runBlocking {
    val disk = singleCache(memoryCache = false) { TestDiskMixedModel() }
    val memory = singleCache(memoryCache = true) { TestDiskMixedModel() }

    assertEquals(true, disk.update { it.copy(name = "disk") })
    assertEquals(TestDiskMixedModel(name = "disk"), memory.get())

    assertEquals(true, memory.update { it.copy(name = "memory") })
    assertEquals(TestDiskMixedModel(name = "memory"), disk.get())
  }

  /** 无法解码的缓存可以删除 */
  @Test
  fun testDeleteUndecodable() = runBlocking {
    assertDeleteUndecodable(
      id = DISK_DELETE_UNDECODABLE_MODEL_ID,
      cache = singleCache(memoryCache = false) { TestDiskDeleteUndecodableModel() },
      defaultValue = TestDiskDeleteUndecodableModel(),
    )
  }

  /** 可空字段和Map中的null写入后原样读回 */
  @Test
  fun testNullRoundTrip() = runBlocking {
    assertNullRoundTrip(
      cache = singleCache(memoryCache = false) { TestDiskNullValueModel() },
      value = TestDiskNullValueModel(name = null, tags = mapOf("key" to null)),
    )
  }

  /** 替换数据文件失败时，内存中不能留下没有落盘的值 */
  @Test
  fun testReplaceFailureNotCached() = runBlocking {
    assertReplaceFailureNotCached(
      id = DISK_REPLACE_FAILURE_MODEL_ID,
      cache = singleCache(memoryCache = false) { TestDiskReplaceFailureModel() },
      value = TestDiskReplaceFailureModel(name = "old"),
      newValue = TestDiskReplaceFailureModel(name = "new"),
    )
  }

  /** 读取失败后、开始监听文件前发生的写入不能漏掉 */
  @Test
  fun testWriteRightAfterReadFailure() = runBlocking {
    assertReceiveWriteRightAfterReadFailure(
      id = DISK_READ_RECOVER_MODEL_ID,
      cache = singleCache(memoryCache = false) { TestDiskReadRecoverModel() },
      defaultValue = TestDiskReadRecoverModel(),
      newValue = TestDiskReadRecoverModel(name = "recovered"),
      newContent = """{"name":"recovered"}""",
    )
  }

  /** 恢复读取时锁被占用，订阅者不能反复重读并上报异常 */
  @Test
  fun testNoBusyRetryWhileLocked() = runBlocking {
    assertNoBusyRetryWhileLocked(
      id = DISK_LOCKED_RECOVER_MODEL_ID,
      cache = singleCache(memoryCache = false) { TestDiskLockedRecoverModel() },
      value = TestDiskLockedRecoverModel(name = "disk"),
      content = """{"name":"disk"}""",
      updatedValue = TestDiskLockedRecoverModel(name = "updated"),
    )
  }

  /** 多个订阅者同时取消后，订阅计数不能丢失 */
  @Test
  fun testCollectorCounterAfterConcurrentCancel() = runBlocking {
    assertCollectorCounterAfterConcurrentCancel(singleCache(memoryCache = false) { TestDiskCollectorCounterModel() })
  }

  /** 没有订阅者时读取不启动文件监听 */
  @Test
  fun testGetNotObservingFile() = runBlocking {
    assertGetNotObservingFile(singleCache(memoryCache = false) { TestDiskGetObserverModel() })
  }

  /** 订阅期间拿锁失败时异常交给ExceptionHandler，进程不能崩溃，之后继续监听 */
  @Test
  fun testLockFailureReported() = runBlocking {
    assertLockFailureReported(
      id = DISK_LOCK_FAILURE_MODEL_ID,
      cache = singleCache(memoryCache = false) { TestDiskLockFailureModel() },
      value = TestDiskLockFailureModel(name = "value"),
      newValue = TestDiskLockFailureModel(name = "new"),
      newContent = """{"name":"new"}""",
    )
  }

  /** 数据目录创建失败后恢复，订阅者能收到之后的写入 */
  @Test
  fun testRecoverAfterDirectoryFailure() = runBlocking {
    assertRecoverAfterDirectoryFailure(
      id = DISK_DIRECTORY_FAILURE_MODEL_ID,
      cache = singleCache(memoryCache = false) { TestDiskDirectoryFailureModel() },
      defaultValue = TestDiskDirectoryFailureModel(),
      updatedValue = TestDiskDirectoryFailureModel(name = "updated"),
      externalValue = TestDiskDirectoryFailureModel(name = "external"),
      externalContent = """{"name":"external"}""",
    )
  }

  /** 计算缓存文件路径失败时Flow不结束，恢复后能收到新值 */
  @Test
  fun testRecoverAfterProduceFileFailure() = runBlocking {
    assertRecoverAfterProduceFileFailure(
      id = DISK_PRODUCE_FILE_FAILURE_MODEL_ID,
      clazz = TestDiskProduceFileFailureModel::class.java,
      memoryCache = false,
      defaultValue = TestDiskProduceFileFailureModel(),
      updatedValue = TestDiskProduceFileFailureModel(name = "updated"),
    )
  }

  /** 调用方的线程被runBlocking阻塞时，同步更新不能死锁 */
  @Test
  fun testNoDeadlockUpdatingOnBlockedThread() = runBlocking {
    assertNoDeadlockUpdatingOnBlockedThread(singleCache(memoryCache = false) { TestDiskBlockedUpdateModel() }) { it.copy(count = it.count + 1) }
  }

  /** 调用方的线程被runBlocking阻塞时，同步读取不能死锁 */
  @Test
  fun testNoDeadlockReadingOnBlockedThread() = runBlocking {
    assertNoDeadlockReadingOnBlockedThread(singleCache(memoryCache = false) { TestDiskBlockedReadModel() })
  }
}

private const val DISK_READ_MODEL_ID = "TestDiskReadModel"
private const val DISK_DELETE_UNDECODABLE_MODEL_ID = "TestDiskDeleteUndecodableModel"
private const val DISK_REPLACE_FAILURE_MODEL_ID = "TestDiskReplaceFailureModel"
private const val DISK_READ_RECOVER_MODEL_ID = "TestDiskReadRecoverModel"
private const val DISK_LOCKED_RECOVER_MODEL_ID = "TestDiskLockedRecoverModel"
private const val DISK_DIRECTORY_FAILURE_MODEL_ID = "TestDiskDirectoryFailureModel"
private const val DISK_LOCK_FAILURE_MODEL_ID = "TestDiskLockFailureModel"
private const val DISK_PRODUCE_FILE_FAILURE_MODEL_ID = "TestDiskProduceFileFailureModel"

@SingleCacheEntity("TestDiskInstanceModel")
data class TestDiskInstanceModel(val name: String = "default")

@SingleCacheEntity("TestDiskDefaultModel")
data class TestDiskDefaultModel(val name: String = "default")

@SingleCacheEntity(DISK_READ_MODEL_ID)
data class TestDiskReadModel(val name: String = "default")

@SingleCacheEntity("TestDiskFlowModel")
data class TestDiskFlowModel(val name: String = "default")

@SingleCacheEntity("TestDiskCounterModel")
data class TestDiskCounterModel(val count: Int = 0)

@SingleCacheEntity("TestDiskMixedModel")
data class TestDiskMixedModel(val name: String = "default")

@SingleCacheEntity(DISK_DELETE_UNDECODABLE_MODEL_ID)
data class TestDiskDeleteUndecodableModel(val name: String = "default")

@SingleCacheEntity(DISK_REPLACE_FAILURE_MODEL_ID)
data class TestDiskReplaceFailureModel(val name: String = "default")

@SingleCacheEntity("TestDiskNullValueModel")
data class TestDiskNullValueModel(
  val name: String? = "default",
  val tags: Map<String, String?> = mapOf("key" to "default"),
)

@SingleCacheEntity(DISK_READ_RECOVER_MODEL_ID)
data class TestDiskReadRecoverModel(val name: String = "default")

@SingleCacheEntity(DISK_LOCKED_RECOVER_MODEL_ID)
data class TestDiskLockedRecoverModel(val name: String = "default")

@SingleCacheEntity(DISK_DIRECTORY_FAILURE_MODEL_ID)
data class TestDiskDirectoryFailureModel(val name: String = "default")

@SingleCacheEntity(DISK_PRODUCE_FILE_FAILURE_MODEL_ID)
data class TestDiskProduceFileFailureModel(val name: String = "default")

@SingleCacheEntity("TestDiskBlockedUpdateModel")
data class TestDiskBlockedUpdateModel(val count: Int = 0)

@SingleCacheEntity("TestDiskBlockedReadModel")
data class TestDiskBlockedReadModel(val name: String = "default")

@SingleCacheEntity("TestDiskCollectorCounterModel")
data class TestDiskCollectorCounterModel(val name: String = "default")

@SingleCacheEntity(DISK_LOCK_FAILURE_MODEL_ID)
data class TestDiskLockFailureModel(val name: String = "default")

@SingleCacheEntity("TestDiskGetObserverModel")
data class TestDiskGetObserverModel(val name: String = "default")
