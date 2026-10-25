package com.sd.lib.singlecache

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow

/** 单值缓存，每个实体类型只保存一个值，支持多进程读写 */
interface SingleCache<T> {

  /** 缓存流，缓存变化时发射最新值 */
  val flow: Flow<T>

  /** 获取当前缓存值 */
  suspend fun get(): T

  /**
   * 更新缓存，[block]在[Dispatchers.IO]上执行，如果[block]返回null则删除缓存，
   * [block]抛出的异常会原样抛给调用方。
   *
   * 不要在[block]里更新缓存：更新同一个缓存会返回false，交叉更新不同的缓存会死锁。
   *
   * @return true-更新成功；false-读取或写入失败
   */
  suspend fun update(block: suspend (T) -> T?): Boolean

  companion object {
    /** 已绑定的缓存id和实体类型，同一个id只能绑定一个类型，否则两个DataStore会读写同一文件 */
    private val sIdClasses = mutableMapOf<String, Class<*>>()

    /** 每个实体类型对应的[SingleCacheStore]，两种模式共用，保证每个缓存文件只有一个DataStore */
    private val sStores = mutableMapOf<Class<*>, SingleCacheStore<*>>()

    /**
     * 获取[clazz]对应的[SingleCache]，每次调用返回新实例并调用一次[getDefault]。
     * 同一类型的所有实例共用同一份缓存，互相能读到对方的更新。
     */
    fun <T> get(
      clazz: Class<T>,
      /** 是否在内存中保留最新值，为false时没有订阅和更新就释放内存，下次读取重新读盘 */
      memoryCache: Boolean = true,
      /** 默认缓存，创建实例时同步调用，只构造简单对象，不要在里面访问缓存 */
      getDefault: () -> T,
    ): SingleCache<T> {
      val store = getStore(clazz)
      return SingleCacheImpl(
        store = store,
        defaultCache = getDefault(),
      ).also {
        if (memoryCache) store.retainInMemory()
      }
    }

    private fun <T> getStore(clazz: Class<T>): SingleCacheStore<T> {
      val store = synchronized(sStores) {
        sStores.getOrPut(clazz) {
          val config = SingleCacheConfig.get()
          val id = entityId(clazz)
          bindId(id = id, clazz = clazz)
          SingleCacheStore(clazz = clazz, produceFile = { config.directory.resolve("${md5(id)}.json") })
        }
      }
      @Suppress("UNCHECKED_CAST")
      return store as SingleCacheStore<T>
    }

    private fun bindId(id: String, clazz: Class<*>) {
      synchronized(sIdClasses) {
        val boundClass = sIdClasses.getOrPut(id) { clazz }
        require(boundClass == clazz) { "id:${id} has bound to ${boundClass.name} when bind ${clazz.name}" }
      }
    }
  }
}

/** 参考[SingleCache.Companion.get] */
inline fun <reified T> singleCache(
  memoryCache: Boolean = true,
  noinline getDefault: () -> T,
): SingleCache<T> {
  return SingleCache.get(
    clazz = T::class.java,
    memoryCache = memoryCache,
    getDefault = getDefault,
  )
}

private fun entityId(clazz: Class<*>): String {
  val annotation = clazz.getAnnotation(SingleCacheEntity::class.java)
  require(annotation != null) { "Annotation ${SingleCacheEntity::class.java.simpleName} was not found in $clazz" }
  // 类型实参在运行时丢失，无法序列化，不同实参还会共用同一个缓存文件
  require(clazz.typeParameters.isEmpty()) { "Type parameters are not supported in $clazz" }

  val id = annotation.id
  require(id.isNotBlank()) { "${SingleCacheEntity::class.java.simpleName}.id is blank in $clazz" }

  try {
    id.encodeToByteArray(throwOnInvalidSequence = true)
  } catch (error: CharacterCodingException) {
    throw IllegalArgumentException(
      "${SingleCacheEntity::class.java.simpleName}.id contains invalid UTF-16 in $clazz",
      error,
    )
  }
  return id
}
