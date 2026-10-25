package com.sd.lib.singlecache

/**
 * 单值缓存实体
 *
 * 混淆时只保留被标注的类，实体中用到的嵌套类和枚举需要自行添加`@Keep`，否则开启R8后可能崩溃或读不出旧缓存。
 * 实体类不能带泛型参数，否则取得实例时抛出[IllegalArgumentException]。
 */
@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.CLASS)
annotation class SingleCacheEntity(
  /** 缓存id，在应用内不能重复，修改后读不到原来的缓存 */
  val id: String,
  /** 是否支持多进程读写，多个进程读写同一缓存时必须设为true，否则可能丢失修改或读到旧值 */
  val multiProcess: Boolean = false,
)
