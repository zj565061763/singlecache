package com.sd.lib.singlecache

import android.annotation.SuppressLint
import android.content.Context
import java.io.File

/** 单值缓存全局配置 */
class SingleCacheConfig private constructor(
  builder: Builder,
  context: Context,
) {
  internal val appContext: Context = context.applicationContext
  internal val directory: File by lazy { appContext.filesDir.resolve("sd.lib.singlecache") }
  internal val exceptionHandler: ExceptionHandler = LibExceptionHandler(builder.exceptionHandler)

  /** 异常处理类 */
  fun interface ExceptionHandler {
    /** 缓存操作异常时回调，可能在任意线程执行，不要在回调中再调用缓存API，也不要抛出异常 */
    fun onException(error: Throwable)
  }

  /** [SingleCacheConfig]构建器 */
  class Builder {
    internal var exceptionHandler: ExceptionHandler? = null
      private set

    /** 设置异常处理 */
    fun setExceptionHandler(handler: ExceptionHandler) = apply {
      this.exceptionHandler = handler
    }

    /** 创建配置 */
    fun build(context: Context): SingleCacheConfig {
      return SingleCacheConfig(this, context)
    }
  }

  companion object {
    @SuppressLint("StaticFieldLeak")
    @Volatile
    private var sConfig: SingleCacheConfig? = null

    /** 初始化，每个进程在Application.onCreate中调用一次，重复调用会抛异常 */
    @JvmStatic
    fun init(config: SingleCacheConfig) {
      synchronized(this@Companion) {
        if (sConfig == null) {
          sConfig = config
        } else {
          error("SingleCacheConfig has been initialized.")
        }
      }
    }

    internal fun get(): SingleCacheConfig {
      return sConfig ?: synchronized(this@Companion) {
        checkNotNull(sConfig) { "SingleCacheConfig.init() should be called before this." }
      }
    }
  }
}

/** 初始化，每个进程在Application.onCreate中调用一次，重复调用会抛异常 */
inline fun SingleCacheConfig.Companion.init(
  context: Context,
  block: SingleCacheConfig.Builder.() -> Unit = {},
) {
  init(
    SingleCacheConfig.Builder()
      .apply(block)
      .build(context)
  )
}

/** 把[error]转给[SingleCacheConfig.ExceptionHandler]，JVM[Error]继续抛出 */
internal fun reportException(error: Throwable) {
  SingleCacheConfig.get().exceptionHandler.onException(error)
}

internal class LibExceptionHandler(
  private val handler: SingleCacheConfig.ExceptionHandler?,
) : SingleCacheConfig.ExceptionHandler {
  override fun onException(error: Throwable) {
    if (error is Error) throw error
    handler?.onException(error)
  }
}
