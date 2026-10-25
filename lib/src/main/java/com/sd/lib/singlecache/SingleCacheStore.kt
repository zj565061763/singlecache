package com.sd.lib.singlecache

import com.sd.lib.singlecache.datastore.DataStore
import com.sd.lib.singlecache.datastore.DataStoreFactory
import com.sd.lib.singlecache.datastore.MultiProcessDataStoreFactory
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 管理[file]对应的DataStore，进程内每个缓存文件只有一个。
 *
 * 保留内存时DataStore一直存在；否则没有使用者时关闭DataStore，释放内存中的值，
 * 下次使用时重新创建并读盘。
 */
internal class SingleCacheStore<T>(
  private val clazz: Class<T>,
  private val multiProcess: Boolean,
  produceFile: () -> File,
) {
  /** 缓存文件，由DataStore首次读写时在IO线程计算，因为获取filesDir会读磁盘 */
  val file: File by lazy(produceFile)

  private val _mutex = Mutex()
  private var _dataStore: DataStore<CacheState<T>>? = null
  private var _dataStoreJob: Job? = null
  private var _dataStoreClosed: CompletableDeferred<Unit>? = null
  private var _users = 0

  @Volatile
  private var _retainInMemory = false

  /** 在内存中保留最新值，之后不再关闭DataStore */
  fun retainInMemory() {
    _retainInMemory = true
  }

  /** 在使用期间保持DataStore打开 */
  suspend fun <R> use(block: suspend (DataStore<CacheState<T>>) -> R): R {
    val dataStore = acquire()
    try {
      return block(dataStore)
    } finally {
      withContext(NonCancellable) { release() }
    }
  }

  private suspend fun acquire(): DataStore<CacheState<T>> {
    return _mutex.withLock {
      val dataStore = _dataStore ?: run {
        // 等上一个DataStore关闭完成，否则同一文件会同时有两个活跃的DataStore
        _dataStoreClosed?.await()
        newDataStore()
      }
      _users++
      dataStore
    }
  }

  private suspend fun release() {
    _mutex.withLock {
      _users--
      if (_users == 0 && !_retainInMemory) {
        _dataStoreJob?.cancel()
        _dataStore = null
      }
    }
  }

  private fun newDataStore(): DataStore<CacheState<T>> {
    val job = SupervisorJob()
    val closed = CompletableDeferred<Unit>()
    val serializer = CacheStateSerializer(clazz)
    // DataStore内部协程没有处理的异常交给ExceptionHandler，否则会让进程崩溃
    val scope = CoroutineScope(Dispatchers.IO + job + CoroutineExceptionHandler { _, e -> reportException(e) })
    // 不传corruptionHandler，原因见SharedCounter
    val dataStore = if (multiProcess) {
      MultiProcessDataStoreFactory.create(serializer = serializer, scope = scope, produceFile = { file })
    } else {
      DataStoreFactory.create(serializer = serializer, scope = scope, produceFile = { file })
    }
    return dataStore.also {
      // 回调按注册顺序执行，在DataStore之后注册，执行时DataStore已关闭
      job.invokeOnCompletion { closed.complete(Unit) }
      _dataStore = it
      _dataStoreJob = job
      _dataStoreClosed = closed
    }
  }
}
