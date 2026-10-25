package com.sd.demo.singlecache

import androidx.test.ext.junit.runners.AndroidJUnit4
import app.cash.turbine.test
import com.sd.lib.singlecache.SingleCache
import com.sd.lib.singlecache.SingleCacheEntity
import com.sd.lib.singlecache.singleCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/** 单进程模式，两种memoryCache都覆盖，跨进程机制见[SingleCacheTest]、[DiskSingleCacheTest] */
@RunWith(AndroidJUnit4::class)
class SingleProcessSingleCacheTest {
  @Test
  fun testUpdateAndGet() = runBlocking {
    assertUpdateAndGet(singleCache { SpUpdateModel() })
  }

  @Test
  fun testUpdateAndGetDisk() = runBlocking {
    assertUpdateAndGet(singleCache(memoryCache = false) { SpDiskUpdateModel() })
  }

  /** 多个订阅者都能收到其他实例的更新 */
  @Test
  fun testFlow() = runBlocking {
    assertFlow { singleCache { SpFlowModel() } }
  }

  @Test
  fun testFlowDisk() = runBlocking {
    assertFlow { singleCache(memoryCache = false) { SpDiskFlowModel() } }
  }

  /** 多个实例并发更新不能丢失修改 */
  @Test
  fun testConcurrentUpdate() = runBlocking {
    assertConcurrentUpdate { singleCache { SpCounterModel() } }
  }

  /** 仅磁盘缓存并发更新期间DataStore会反复关闭和重新创建 */
  @Test
  fun testConcurrentUpdateDisk() = runBlocking {
    assertConcurrentUpdate { singleCache(memoryCache = false) { SpDiskCounterModel() } }
  }

  /** 快速连续更新时，订阅者最终收到最新值 */
  @Test
  fun testRapidUpdate() = runBlocking {
    assertRapidUpdate(singleCache { SpRapidModel() })
  }

  @Test
  fun testRapidUpdateDisk() = runBlocking {
    assertRapidUpdate(singleCache(memoryCache = false) { SpDiskRapidModel() })
  }

  /** 仅磁盘缓存没有使用者时不保留内存值，下次读取重新读盘 */
  @Test
  fun testReadFromDiskAfterRelease() = runBlocking {
    val cache = singleCache(memoryCache = false) { SpDiskReadModel() }
    assertEquals(true, cache.update { it.withCount(1) })
    assertEquals(SpDiskReadModel(count = 1), cache.get())

    writeCacheFileDirectly(SP_DISK_READ_MODEL_ID, """{"count":2}""")
    assertEquals(SpDiskReadModel(count = 2), cache.get())
  }

  /** 同一类型的两种模式共用一个DataStore，互相能读到对方的更新 */
  @Test
  fun testMixedMode() = runBlocking {
    val disk = singleCache(memoryCache = false) { SpMixedModel() }
    val memory = singleCache { SpMixedModel() }

    assertEquals(true, disk.update { it.withCount(1) })
    assertEquals(SpMixedModel(count = 1), memory.get())

    assertEquals(true, memory.update { it.withCount(2) })
    assertEquals(SpMixedModel(count = 2), disk.get())
  }

  @Test
  fun testDeleteUndecodable() = runBlocking {
    assertDeleteUndecodable(SP_DELETE_UNDECODABLE_MODEL_ID, singleCache { SpDeleteUndecodableModel() }, SpDeleteUndecodableModel())
  }

  @Test
  fun testDeleteUndecodableDisk() = runBlocking {
    assertDeleteUndecodable(SP_DISK_DELETE_UNDECODABLE_MODEL_ID, singleCache(memoryCache = false) { SpDiskDeleteUndecodableModel() }, SpDiskDeleteUndecodableModel())
  }

  @Test
  fun testNullRoundTrip() = runBlocking {
    assertNullRoundTrip(singleCache { SpNullValueModel() }, SpNullValueModel(name = null))
  }

  @Test
  fun testNullRoundTripDisk() = runBlocking {
    assertNullRoundTrip(singleCache(memoryCache = false) { SpDiskNullValueModel() }, SpDiskNullValueModel(name = null))
  }

  /** 替换数据文件失败时，内存中不能留下没有落盘的值 */
  @Test
  fun testReplaceFailureNotCached() = runBlocking {
    assertReplaceFailureNotCached(SP_REPLACE_FAILURE_MODEL_ID, singleCache { SpReplaceFailureModel() }, SpReplaceFailureModel(count = 1), SpReplaceFailureModel(count = 2))
  }

  @Test
  fun testReplaceFailureNotCachedDisk() = runBlocking {
    assertReplaceFailureNotCached(SP_DISK_REPLACE_FAILURE_MODEL_ID, singleCache(memoryCache = false) { SpDiskReplaceFailureModel() }, SpDiskReplaceFailureModel(count = 1), SpDiskReplaceFailureModel(count = 2))
  }

  /** 读取失败后、开始监听文件前发生的写入不能漏掉 */
  @Test
  fun testWriteRightAfterReadFailure() = runBlocking {
    assertReceiveWriteRightAfterReadFailure(SP_READ_RECOVER_MODEL_ID, singleCache { SpReadRecoverModel() }, SpReadRecoverModel(), SpReadRecoverModel(count = 1), """{"count":1}""")
  }

  @Test
  fun testWriteRightAfterReadFailureDisk() = runBlocking {
    assertReceiveWriteRightAfterReadFailure(SP_DISK_READ_RECOVER_MODEL_ID, singleCache(memoryCache = false) { SpDiskReadRecoverModel() }, SpDiskReadRecoverModel(), SpDiskReadRecoverModel(count = 1), """{"count":1}""")
  }

  /** 调用方的线程被runBlocking阻塞时，同步读写不能死锁 */
  @Test
  fun testNoDeadlockOnBlockedThread() = runBlocking {
    assertNoDeadlockUpdatingOnBlockedThread(singleCache { SpBlockedUpdateModel() }) { it.withCount(it.count + 1) }
    assertNoDeadlockReadingOnBlockedThread(singleCache { SpBlockedReadModel() })
  }

  @Test
  fun testNoDeadlockOnBlockedThreadDisk() = runBlocking {
    assertNoDeadlockUpdatingOnBlockedThread(singleCache(memoryCache = false) { SpDiskBlockedUpdateModel() }) { it.withCount(it.count + 1) }
    assertNoDeadlockReadingOnBlockedThread(singleCache(memoryCache = false) { SpDiskBlockedReadModel() })
  }

  /** 不注册文件监听，不生成锁文件和版本文件 */
  @Test
  fun testNoMultiProcessFiles() = runBlocking {
    assertNoMultiProcessFiles(SP_NO_MULTI_PROCESS_FILES_MODEL_ID, singleCache { SpNoMultiProcessFilesModel() }, SpNoMultiProcessFilesModel(count = 1))
  }

  @Test
  fun testNoMultiProcessFilesDisk() = runBlocking {
    assertNoMultiProcessFiles(SP_DISK_NO_MULTI_PROCESS_FILES_MODEL_ID, singleCache(memoryCache = false) { SpDiskNoMultiProcessFilesModel() }, SpDiskNoMultiProcessFilesModel(count = 1))
  }
}

private suspend fun <T : SingleProcessCounter<T>> assertUpdateAndGet(cache: SingleCache<T>) {
  assertEquals(true, cache.update { null })
  val defaultValue = cache.get()

  assertEquals(true, cache.update { it.withCount(1) })
  assertEquals(defaultValue.withCount(1), cache.get())

  // 返回null删除缓存，还原为默认值
  assertEquals(true, cache.update { null })
  assertEquals(defaultValue, cache.get())
}

private suspend fun <T : SingleProcessCounter<T>> assertFlow(newCache: () -> SingleCache<T>) = coroutineScope<Unit> {
  val cache = newCache()
  assertEquals(true, cache.update { null })
  val expect = cache.get().withCount(1)

  val subscribers = List(3) {
    async(Dispatchers.Default) {
      cache.flow.test(timeout = TEST_TIMEOUT) { awaitItemUntil(expect) }
    }
  }
  assertEquals(true, newCache().update { expect })
  subscribers.awaitAll()
}

private suspend fun <T : SingleProcessCounter<T>> assertConcurrentUpdate(newCache: () -> SingleCache<T>) = coroutineScope<Unit> {
  assertEquals(true, newCache().update { null })

  List(100) {
    async(Dispatchers.Default) { newCache().update { it.withCount(it.count + 1) } }
  }.awaitAll().forEach { assertEquals(true, it) }
  assertEquals(100, newCache().get().count)
}

private suspend fun <T : SingleProcessCounter<T>> assertRapidUpdate(cache: SingleCache<T>) {
  assertEquals(true, cache.update { null })
  val defaultValue = cache.get()

  cache.flow.test(timeout = TEST_TIMEOUT) {
    assertEquals(defaultValue, awaitItem())
    val job = launch(Dispatchers.Default) {
      repeat(50) { cache.update { it.withCount(it.count + 1) } }
    }
    awaitItemUntil(defaultValue.withCount(50))
    job.join()
  }
}

/** 单进程测试的计数模型 */
interface SingleProcessCounter<T : SingleProcessCounter<T>> {
  val count: Int

  fun withCount(count: Int): T
}

private const val SP_DISK_READ_MODEL_ID = "SpDiskReadModel"
private const val SP_DELETE_UNDECODABLE_MODEL_ID = "SpDeleteUndecodableModel"
private const val SP_DISK_DELETE_UNDECODABLE_MODEL_ID = "SpDiskDeleteUndecodableModel"
private const val SP_REPLACE_FAILURE_MODEL_ID = "SpReplaceFailureModel"
private const val SP_DISK_REPLACE_FAILURE_MODEL_ID = "SpDiskReplaceFailureModel"
private const val SP_READ_RECOVER_MODEL_ID = "SpReadRecoverModel"
private const val SP_DISK_READ_RECOVER_MODEL_ID = "SpDiskReadRecoverModel"
private const val SP_NO_MULTI_PROCESS_FILES_MODEL_ID = "SpNoMultiProcessFilesModel"
private const val SP_DISK_NO_MULTI_PROCESS_FILES_MODEL_ID = "SpDiskNoMultiProcessFilesModel"

@SingleCacheEntity("SpUpdateModel")
data class SpUpdateModel(override val count: Int = 0) : SingleProcessCounter<SpUpdateModel> {
  override fun withCount(count: Int) = copy(count = count)
}

@SingleCacheEntity("SpDiskUpdateModel")
data class SpDiskUpdateModel(override val count: Int = 0) : SingleProcessCounter<SpDiskUpdateModel> {
  override fun withCount(count: Int) = copy(count = count)
}

@SingleCacheEntity("SpFlowModel")
data class SpFlowModel(override val count: Int = 0) : SingleProcessCounter<SpFlowModel> {
  override fun withCount(count: Int) = copy(count = count)
}

@SingleCacheEntity("SpDiskFlowModel")
data class SpDiskFlowModel(override val count: Int = 0) : SingleProcessCounter<SpDiskFlowModel> {
  override fun withCount(count: Int) = copy(count = count)
}

@SingleCacheEntity("SpCounterModel")
data class SpCounterModel(override val count: Int = 0) : SingleProcessCounter<SpCounterModel> {
  override fun withCount(count: Int) = copy(count = count)
}

@SingleCacheEntity("SpDiskCounterModel")
data class SpDiskCounterModel(override val count: Int = 0) : SingleProcessCounter<SpDiskCounterModel> {
  override fun withCount(count: Int) = copy(count = count)
}

@SingleCacheEntity("SpRapidModel")
data class SpRapidModel(override val count: Int = 0) : SingleProcessCounter<SpRapidModel> {
  override fun withCount(count: Int) = copy(count = count)
}

@SingleCacheEntity("SpDiskRapidModel")
data class SpDiskRapidModel(override val count: Int = 0) : SingleProcessCounter<SpDiskRapidModel> {
  override fun withCount(count: Int) = copy(count = count)
}

@SingleCacheEntity(SP_DISK_READ_MODEL_ID)
data class SpDiskReadModel(override val count: Int = 0) : SingleProcessCounter<SpDiskReadModel> {
  override fun withCount(count: Int) = copy(count = count)
}

@SingleCacheEntity("SpMixedModel")
data class SpMixedModel(override val count: Int = 0) : SingleProcessCounter<SpMixedModel> {
  override fun withCount(count: Int) = copy(count = count)
}

@SingleCacheEntity(SP_DELETE_UNDECODABLE_MODEL_ID)
data class SpDeleteUndecodableModel(val count: Int = 0)

@SingleCacheEntity(SP_DISK_DELETE_UNDECODABLE_MODEL_ID)
data class SpDiskDeleteUndecodableModel(val count: Int = 0)

@SingleCacheEntity("SpNullValueModel")
data class SpNullValueModel(val name: String? = "default")

@SingleCacheEntity("SpDiskNullValueModel")
data class SpDiskNullValueModel(val name: String? = "default")

@SingleCacheEntity(SP_REPLACE_FAILURE_MODEL_ID)
data class SpReplaceFailureModel(val count: Int = 0)

@SingleCacheEntity(SP_DISK_REPLACE_FAILURE_MODEL_ID)
data class SpDiskReplaceFailureModel(val count: Int = 0)

@SingleCacheEntity(SP_READ_RECOVER_MODEL_ID)
data class SpReadRecoverModel(val count: Int = 0)

@SingleCacheEntity(SP_DISK_READ_RECOVER_MODEL_ID)
data class SpDiskReadRecoverModel(val count: Int = 0)

@SingleCacheEntity("SpBlockedUpdateModel")
data class SpBlockedUpdateModel(override val count: Int = 0) : SingleProcessCounter<SpBlockedUpdateModel> {
  override fun withCount(count: Int) = copy(count = count)
}

@SingleCacheEntity("SpBlockedReadModel")
data class SpBlockedReadModel(val count: Int = 0)

@SingleCacheEntity("SpDiskBlockedUpdateModel")
data class SpDiskBlockedUpdateModel(override val count: Int = 0) : SingleProcessCounter<SpDiskBlockedUpdateModel> {
  override fun withCount(count: Int) = copy(count = count)
}

@SingleCacheEntity("SpDiskBlockedReadModel")
data class SpDiskBlockedReadModel(val count: Int = 0)

@SingleCacheEntity(SP_NO_MULTI_PROCESS_FILES_MODEL_ID)
data class SpNoMultiProcessFilesModel(val count: Int = 0)

@SingleCacheEntity(SP_DISK_NO_MULTI_PROCESS_FILES_MODEL_ID)
data class SpDiskNoMultiProcessFilesModel(val count: Int = 0)
