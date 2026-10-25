package com.sd.lib.singlecache

import com.sd.lib.singlecache.datastore.Serializer
import java.io.InputStream
import java.io.OutputStream

/** 保存在DataStore中的缓存状态 */
internal sealed interface CacheState<out T> {
  /** 无缓存 */
  data object Absent : CacheState<Nothing>

  /** 有缓存 */
  data class Present<T>(val value: T) : CacheState<T>

  /** 无法解码，读取时视为无缓存；与[Absent]区分，删除时新旧值不相等才会写入空文件 */
  data object Undecodable : CacheState<Nothing>
}

/** [CacheState]序列化器，空文件表示[CacheState.Absent]，无法解码表示[CacheState.Undecodable] */
internal class CacheStateSerializer<T>(
  private val clazz: Class<T>,
) : Serializer<CacheState<T>> {
  override val defaultValue: CacheState<T>
    get() = CacheState.Absent

  override suspend fun readFrom(input: InputStream): CacheState<T> {
    val bytes = input.readBytes()
    if (bytes.isEmpty()) return CacheState.Absent
    // 解码失败视为无缓存，不抛CorruptionException，否则DataStore会当作读取失败
    return runCatching {
      CacheState.Present(DefaultObjectConverter.decode(bytes, clazz))
    }.getOrElse {
      reportException(it)
      CacheState.Undecodable
    }
  }

  override suspend fun writeTo(t: CacheState<T>, output: OutputStream) {
    when (t) {
      CacheState.Absent, CacheState.Undecodable -> Unit
      is CacheState.Present -> output.write(DefaultObjectConverter.encode(t.value, clazz))
    }
  }
}
