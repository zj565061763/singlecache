[![Maven Central](https://img.shields.io/maven-central/v/io.github.zj565061763.android/singlecache)](https://central.sonatype.com/search?q=g:io.github.zj565061763.android+singlecache)

单值缓存库：每个实体类型只保存一个值，支持多进程读写。

# Gradle

```kotlin
implementation("io.github.zj565061763.android:singlecache:$version")
```

- 本库不会传递 Kotlin 协程依赖，接入方必须自行声明协程依赖（1.8.1 及以上）。
- 本库不依赖 DataStore 的 artifact，和接入方自己的 DataStore 可以同时使用。

版本更新记录请查看 [CHANGELOG.md](CHANGELOG.md)。

# 使用

```kotlin
// 每个进程都要在 Application.onCreate() 中初始化一次
SingleCacheConfig.init(context) {
  setExceptionHandler { error -> Log.e("SingleCache", "error", error) }
}

@SingleCacheEntity(id = "UserProfile")
data class UserProfile(val name: String = "")

val cache = singleCache { UserProfile() }                        // 内存缓存
val diskCache = singleCache(memoryCache = false) { UserProfile() } // 仅磁盘缓存

cache.flow.collect { profile -> }          // 缓存变化时发射最新值
cache.get()                                // 读取当前值
cache.update { it.copy(name = "tom") }     // 更新，返回 false 表示读写失败
cache.update { null }                      // 删除，之后读到默认值
```

# 两种模式

| 项目 | `memoryCache = true`（默认） | `memoryCache = false` |
|---|---|---|
| 内存中的值 | 一直保留 | 没有订阅和进行中的 `update` 时释放 |
| 读取 | 直接用内存中的值，其他进程修改后才重新读盘 | 释放后的下一次读取重新读盘 |

- 每次调用 `singleCache` 都返回新实例，并调用一次 `getDefault`，每个实例使用自己的默认值。
- 同一类型的所有实例共用同一份缓存，互相能读到对方的更新。
- 两种模式可以混用。
- 只要进程内创建过这个类型的内存缓存，它的值就会一直保留在内存中。

# 行为

- 多个进程同时 `update` 不会丢失修改。
- `block` 是挂起函数，在 `Dispatchers.IO` 上执行。
- `block` 抛出的异常原样抛给调用方。
- 不要在 `block` 里更新缓存：更新同一个缓存会返回 `false`，交叉更新不同的缓存会死锁。
- 不要在 `block` 里读取其他缓存：多个进程交叉读取时，一方会等待一分多钟，然后读到默认值。
- 可以在任意线程用 `runBlocking` 同步读写，不会死锁（`block` 里读写其他缓存除外）。
- 其他进程的修改，`flow` 会稍后收到。
- `get()` 能立即读到其他进程已完成的修改。
- 缓存值必须不可变：在 `block` 里修改传入的值，`update` 可能失败、可能不保存修改，也可能改掉该实例的默认值。
- 修改 `get()` 或 `flow` 读到的值不会写入磁盘，同进程其他地方可能读到修改后的值。
- 读写失败时，异常交给 `ExceptionHandler`，可能在任意线程回调。
- `ExceptionHandler` 中不能再调用缓存 API。
- `ExceptionHandler` 中不要抛出异常，否则可能导致进程崩溃。
- 解码失败视为无缓存，`update` 可以覆盖或删除。
- 字段从可空改为不可空后，旧缓存里这个字段为 `null` 时整个缓存解码失败。
- 读取失败时 `flow` 保留当前值，首次读取失败发射默认值；缓存下一次变化时重新读取。
- 数据目录创建失败时，`flow` 每 10 秒重新读取一次，每次失败都会回调 `ExceptionHandler`。
- `@SingleCacheEntity` 缺失或 `id` 非法（空白、含非法 UTF-16）时，取得实例立即抛 `IllegalArgumentException`。
- 多个类型使用同一个 `id` 时，取得实例立即抛 `IllegalArgumentException`。
- 实体类带泛型参数（如 `Box<T>`）时，取得实例立即抛 `IllegalArgumentException`。
- 不支持运行中删除数据目录 `filesDir/sd.lib.singlecache`。

# 混淆

本库只保留 `@SingleCacheEntity` 实体类本身。实体中用到的嵌套类和枚举（包括集合元素类型）需要自行保留，否则开启 R8 后：

- 读写含枚举的缓存会崩溃
- 嵌套类的字段名被混淆，版本更新后旧缓存可能读不出来

推荐添加 `@Keep`：

```kotlin
@SingleCacheEntity(id = "UserProfile")
data class UserProfile(
  val level: Level = Level.Normal,
  val address: Address = Address(),
)

@Keep
enum class Level { Normal, Vip }

@Keep
data class Address(val city: String = "")
```

# DataStore 源码

`lib/src/main/java/com/sd/lib/singlecache/datastore/` 是 `androidx.datastore:datastore-core-android:1.2.1` 的源码，来自 sources jar，版权归 The Android Open Source Project，按 Apache 2.0 许可使用。
