package com.sd.demo.singlecache

import android.app.Service
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.SystemClock
import com.sd.lib.singlecache.SingleCache
import com.sd.lib.singlecache.SingleCacheEntity
import com.sd.lib.singlecache.singleCache
import kotlinx.coroutines.runBlocking

/** 供instrumentation测试在独立进程中读写单值缓存 */
class MultiProcessTestService : Service() {
  private val _messenger = Messenger(Handler(Looper.getMainLooper()) { message ->
    val replyTo = message.replyTo ?: return@Handler false
    // Message在处理完后会被回收，参数要在切换线程前读出来
    val data = Bundle(message.data)
    when (message.what) {
      COMMAND_INCREMENT -> runInThread(replyTo, message.what) {
        val counter = counter(data)
        val times = data.getInt(EXTRA_TIMES)
        waitUntil(data.getLong(EXTRA_START_AT))
        var success = 0
        repeat(times) {
          if (counter.increment()) success++
        }
        success
      }
      COMMAND_SET -> runInThread(replyTo, message.what) {
        val value = data.getInt(EXTRA_VALUE)
        if (counter(data).set(value)) 1 else 0
      }
      COMMAND_GET -> runInThread(replyTo, message.what) {
        counter(data).get()
      }
      else -> return@Handler false
    }
    true
  })

  override fun onBind(intent: Intent?): IBinder = _messenger.binder

  companion object {
    const val COMMAND_INCREMENT = 1
    const val COMMAND_SET = 2
    const val COMMAND_GET = 3

    private const val EXTRA_TIMES = "times"
    private const val EXTRA_START_AT = "start_at"
    private const val EXTRA_VALUE = "value"
    private const val EXTRA_DISK_MODEL = "disk_model"

    // 远端进程都用内存缓存
    private val sCounter by lazy { RemoteCounter(singleCache { MultiProcessCounterModel() }) }
    private val sDiskCounter by lazy { RemoteCounter(singleCache { MultiProcessDiskCounterModel() }) }

    /** 命令要操作的计数缓存 */
    private fun counter(data: Bundle): RemoteCounter<*> {
      return if (data.getBoolean(EXTRA_DISK_MODEL)) sDiskCounter else sCounter
    }

    /** 在[startAt]时刻开始，连续把计数加一[times]次，回复成功次数；[diskModel]为true时操作[MultiProcessDiskCounterModel] */
    fun newIncrementMessage(diskModel: Boolean, times: Int, startAt: Long, replyTo: Messenger): Message {
      return Message.obtain(null, COMMAND_INCREMENT).apply {
        data = Bundle().apply {
          putBoolean(EXTRA_DISK_MODEL, diskModel)
          putInt(EXTRA_TIMES, times)
          putLong(EXTRA_START_AT, startAt)
        }
        this.replyTo = replyTo
      }
    }

    /** 把计数设置为[value]，成功回复1，失败回复0；[diskModel]为true时操作[MultiProcessDiskCounterModel] */
    fun newSetMessage(diskModel: Boolean, value: Int, replyTo: Messenger): Message {
      return Message.obtain(null, COMMAND_SET).apply {
        data = Bundle().apply {
          putBoolean(EXTRA_DISK_MODEL, diskModel)
          putInt(EXTRA_VALUE, value)
        }
        this.replyTo = replyTo
      }
    }

    /** 读取当前计数；[diskModel]为true时操作[MultiProcessDiskCounterModel] */
    fun newGetMessage(diskModel: Boolean, replyTo: Messenger): Message {
      return Message.obtain(null, COMMAND_GET).apply {
        data = Bundle().apply { putBoolean(EXTRA_DISK_MODEL, diskModel) }
        this.replyTo = replyTo
      }
    }
  }
}

/** 跨进程测试的计数模型 */
interface MultiProcessCounter<T : MultiProcessCounter<T>> {
  val count: Int

  fun withCount(count: Int): T
}

@SingleCacheEntity("MultiProcessCounterModel", multiProcess = true)
data class MultiProcessCounterModel(
  override val count: Int = 0,
) : MultiProcessCounter<MultiProcessCounterModel> {
  override fun withCount(count: Int) = copy(count = count)
}

/** 测试进程只以仅磁盘缓存使用，创建过内存缓存后DataStore不再关闭，就测不到仅磁盘缓存 */
@SingleCacheEntity("MultiProcessDiskCounterModel", multiProcess = true)
data class MultiProcessDiskCounterModel(
  override val count: Int = 0,
) : MultiProcessCounter<MultiProcessDiskCounterModel> {
  override fun withCount(count: Int) = copy(count = count)
}

/** 远端进程对计数缓存的读写 */
private class RemoteCounter<T : MultiProcessCounter<T>>(private val cache: SingleCache<T>) {
  suspend fun increment(): Boolean = cache.update { it.withCount(it.count + 1) }

  suspend fun set(count: Int): Boolean = cache.update { it.withCount(count) }

  suspend fun get(): Int = cache.get().count
}

/** 在独立线程中执行[block]，把结果放在arg1中回复，what与命令相同 */
private fun runInThread(replyTo: Messenger, what: Int, block: suspend () -> Int) {
  Thread({
    val result = runBlocking { block() }
    val response = Message.obtain(null, what).apply { arg1 = result }
    runCatching { replyTo.send(response) }
  }, "cache-test-remote").start()
}

private fun waitUntil(startAt: Long) {
  while (true) {
    val remaining = startAt - SystemClock.elapsedRealtime()
    if (remaining <= 0) return
    Thread.sleep(remaining.coerceAtMost(10))
  }
}
