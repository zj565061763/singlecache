package com.sd.demo.singlecache

import android.app.Application
import android.util.Log
import com.sd.lib.singlecache.SingleCacheConfig
import com.sd.lib.singlecache.SingleCacheEntity
import com.sd.lib.singlecache.init
import java.util.Collections

class App : Application() {
  override fun onCreate() {
    super.onCreate()
    // 每个进程都会执行，所以每个进程都完成了初始化
    SingleCacheConfig.init(this) {
      setExceptionHandler { error ->
        logMsg { "error:${error}" }
        CacheErrors.add(error)
      }
    }
  }
}

@SingleCacheEntity("CounterModel")
data class CounterModel(
  val count: Int = 0,
)

/**
 * 测试用：记录[SingleCacheConfig]的异常处理器收到的异常。
 * [SingleCacheConfig.init]只能调用一次、且发生在[Application.onCreate]，测试没办法替换异常处理器，
 * 所以只能在这里开一个口子，让测试能断言异常的内容。
 */
object CacheErrors {
  private val _errors = Collections.synchronizedList(mutableListOf<Throwable>())

  /** 收到异常时同步执行，测试用它在读取失败后、重新监听文件前写入 */
  @Volatile
  var onAdd: ((Throwable) -> Unit)? = null

  fun add(error: Throwable) {
    _errors.add(error)
    onAdd?.invoke(error)
  }

  fun clear() {
    _errors.clear()
  }

  fun list(): List<Throwable> = _errors.toList()
}

inline fun logMsg(block: () -> String) {
  Log.i("sd-demo", block())
}
