package com.sd.lib.singlecache

import com.sd.lib.singlecache.datastore.MulticastFileObserver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.produceIn
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException

/** 数据目录不存在时，读取失败后重读的间隔 */
private const val RETRY_DELAY_MILLIS = 10_000L

internal class SingleCacheImpl<T>(
  private val store: SingleCacheStore<T>,
  private val defaultCache: T,
) : SingleCache<T> {

  override val flow: Flow<T> = flow {
    store.use { dataStore ->
      coroutineScope {
        var emitted = false
        // 读取失败后开始监听文件，直到收集结束，收到值不代表DataStore已恢复
        var fileChanges: ReceiveChannel<Unit>? = null
        try {
          while (true) {
            var readError: Throwable? = null
            dataStore.data
              .catch { readError = it }
              .collect {
                emit(it.valueOrDefault())
                emitted = true
              }
            // 没有异常时data结束，说明DataStore已关闭
            val error = readError ?: break
            reportException(error)
            // 读取失败时保留当前值，首次读取失败则发射默认值，避免订阅者一直等待
            if (!emitted) {
              emit(defaultCache)
              emitted = true
            }
            // 目录不存在时注册的监听无效，还会让同目录后来的监听一起失效，改为延时重读
            if (fileChanges == null && !directoryExists()) {
              delay(RETRY_DELAY_MILLIS)
              continue
            }
            // 注册完成的首个事件立即触发重读，不会漏掉失败到注册之间的写入
            val changes = fileChanges ?: MulticastFileObserver.observe(store.file).conflate().produceIn(this).also { fileChanges = it }
            changes.receive()
          }
        } finally {
          fileChanges?.cancel()
        }
      }
    }
  }
    // 加锁和等锁都在IO线程，调用方的线程被阻塞也不会死锁
    .flowOn(Dispatchers.IO)
    .conflate()
    .distinctUntilChanged()

  override suspend fun get(): T {
    // 不能用flow.first()：flowOn让上游在首个值之后继续执行，每次读取都会启动DataStore的文件监听
    return withContext(Dispatchers.IO) {
      store.use { dataStore ->
        try {
          dataStore.data.first().valueOrDefault()
        } catch (e: Throwable) {
          if (e is CancellationException) throw e
          // 读取失败时交给ExceptionHandler并返回默认值
          reportException(e)
          defaultCache
        }
      }
    }
  }

  override suspend fun update(block: suspend (T) -> T?): Boolean {
    var blockError: Throwable? = null
    return try {
      // DataStore在调用方的上下文中执行transform，整体切到IO，持锁期间才不依赖调用方的线程
      withContext(Dispatchers.IO) {
        store.use { dataStore ->
          dataStore.updateData { cache ->
            val newCache = try {
              block(cache.valueOrDefault())
            } catch (e: Throwable) {
              blockError = e
              throw e
            }
            if (newCache == null) CacheState.Absent else CacheState.Present(newCache)
          }
        }
      }
      true
    } catch (e: Throwable) {
      blockError?.also { throw it }
      if (e is CancellationException) throw e
      reportException(e)
      false
    }
  }

  /** 数据目录是否存在，计算缓存文件路径失败时视为不存在，比如API 23及以下filesDir返回null */
  private fun directoryExists(): Boolean {
    return try {
      store.file.parentFile?.isDirectory == true
    } catch (_: Exception) {
      // 读取时已经因为同样的原因失败并上报过，不重复上报
      false
    }
  }

  private fun CacheState<T>.valueOrDefault(): T {
    return when (this) {
      CacheState.Absent, CacheState.Undecodable -> defaultCache
      is CacheState.Present -> value
    }
  }
}
