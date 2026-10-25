# Repository Guidelines

## 项目定位与目录

Android 单值缓存库，Maven 坐标 `io.github.zj565061763.android:singlecache`，最低 API 21，编译 SDK 35，Kotlin 1.9.25。

- 库代码：`lib/src/main/java/com/sd/lib/singlecache/`
- 内置的 DataStore 源码：`lib/src/main/java/com/sd/lib/singlecache/datastore/`
- 示例：`app/src/main/`；多进程测试服务：`app/src/debug/`
- 测试：全部在 `app/src/androidTest/`，没有本地 JVM 测试
- 依赖版本：`gradle/libs.versions.toml`；发布坐标及版本：`lib/gradle.properties`

调用链：`singleCache → SingleCache.get → SingleCacheImpl → SingleCacheStore → DataStore（MultiProcessDataStoreFactory）→ CacheStateSerializer → DefaultObjectConverter`。

## datastore 目录

- 来源是 `androidx.datastore:datastore-core-android:1.2.1` 的 sources jar。
- 只能做下表中列出的改动；新增改动必须补进该表。
- 修 bug 优先移植上游 androidx-main 的实现，并在代码注释和表格中注明。
- 所有声明保持 `internal`，不能成为本库的公开 API。
- 不能引入 native 代码，共享计数器保持 Java mmap 实现。

相对上游的全部改动：

| 改动 | 文件 | 原因 |
|---|---|---|
| 包名 `androidx.datastore.core` 改为 `com.sd.lib.singlecache.datastore` | 全部 | 避免与接入方的 DataStore 类名冲突 |
| 所有顶层声明改为 `internal` | 全部 | 不暴露 DataStore API |
| 每个文件的版权头后加一行修改说明 | 全部 | Apache 2.0 要求标注修改 |
| KMP 源码集展平到一个目录，删除只有 `expect` 声明的文件，去掉 `actual` 修饰符 | `Expect.kt`、`DataStoreFactory.kt`、`DirectBootExceptionUtil.kt`、`FileMoves.kt`、`handlers/NoOpCorruptionHandler.kt`、`handlers/ReplaceFileCorruptionHandler.kt` 已删除；`Actual.jvm.kt`、`DataStoreFactory.android.kt`、`DirectBootExceptionUtil.android.kt`、`FileMoves.android.kt`、`handlers/*.jvm.kt` 去掉 `actual` | 普通 Android 模块不能编译 `expect/actual` |
| `expect` 声明上的默认参数移到实现上 | `Actual.jvm.kt` 的 `AtomicInt`、`DataStoreFactory.android.kt` 的 `create(storage, ...)` | 展平后保持原有调用方式 |
| 用 Java mmap 重写共享计数器，删除 JNI 和 Robolectric 替身 | `SharedCounter.android.kt` | 不带 native 库，避免和 DataStore 的 so 冲突；自增只在排他文件锁内发生，不需要原子指令 |
| 移植上游 androidx-main 的修复：没拿到锁的读取使用已缓存的版本号 | `DataStoreImpl.kt` 的 `readDataAndUpdateCache`、`readDataOrHandleCorruption` | 1.2.1 中，订阅与写入并发时，订阅者可能收不到这次写入 |
| 移植上游 androidx-main 的修复：替换数据文件时加 `ATOMIC_MOVE`，写入过程中的 `IOException` 都包装为重命名失败，原始异常作为 cause（b/203087070） | `FileMoves.android.kt`、`FileStorage.kt` 的 `writeScope` | 1.2.1 在 API 26 及以上先删除数据文件再重命名，中间不加锁的读取会读到默认值，进程被杀会丢失数据 |
| 本库的修复，上游尚未修复（b/567175136）：订阅计数加一成功才减一，减一不可取消 | `DataStoreImpl.kt` 的 `data`、`decrementCollector` | 多个订阅者同时取消时计数会丢失：计数偏大则文件监听不释放，计数偏小则后来的订阅者收不到其他进程的写入 |
| 本库的修复，上游尚未修复（b/567169229）：注册文件监听后发送首个事件失败时释放监听 | `MulticastFileObserver.android.kt` 的 `observe(file)` | 注册过程中被取消时监听泄漏，同目录的 `FileObserver` 一直不停止；测试清空目录后，同目录的监听都收不到事件 |
| 本库的修复，上游尚未修复（b/567175140）：替换数据文件成功后才更新内存缓存 | `DataStoreImpl.kt` 的 `writeData` | 更新内存原本在替换文件之前，替换失败时 `update` 返回 `false`，内存却留着没落盘的值；版本号已更新，本进程在下一次写入前一直读到这个值 |
| 本库的修复，上游尚未修复：文件变化触发的重读拿锁失败时上报异常并继续监听 | `DataStoreImpl.kt` 的 `incrementCollector` | 监听协程原本会结束，订阅计数不归零就不会重启，订阅者一直收不到其他进程的更新；上游默认的 scope 没有 `CoroutineExceptionHandler`，会让进程崩溃 |

## 初始化与公开 API

```kotlin
@SingleCacheEntity(id = "UserProfile")
data class UserProfile(val name: String = "")

val cache = singleCache { UserProfile() }                        // 内存缓存
val diskCache = singleCache(memoryCache = false) { UserProfile() } // 仅磁盘缓存
```

- `SingleCacheConfig.init(context)` 在每个进程的 `Application.onCreate()` 中调用一次，重复调用会失败。
- 配置只开放 `ExceptionHandler`；序列化固定使用 Moshi（`DefaultObjectConverter`）。
- `@SingleCacheEntity.id` 不能为空白或含非法 UTF-16，在应用内不能重复。
- 实体类不能带泛型参数：`T::class.java` 丢失类型实参，Moshi 无法序列化，不同实参还会共用同一个缓存文件。
- 每次调用都返回新实例，并调用一次 `getDefault`；同一类型的实例共用一个 `SingleCacheStore`。
- `memoryCache=true`（默认）：内存值一直保留。
- `memoryCache=false`：没有使用者时释放内存值。
- `update` 的 lambda 返回 `null` 表示删除，之后读到默认值。
- `update` 的 `block` 是挂起函数，在 `Dispatchers.IO` 上执行。
- `kotlinx-coroutines` 有意使用 `compileOnly`，由接入方自行声明。不要改成 `api`/`implementation`，审查时也不要列为依赖遗漏。
- Kotlin 1.9.25 读不了 Kotlin 2.1 及以上编译的元数据，协程依赖不能升到 1.10 及以上。
- Moshi 同理，升级前确认新版本的 Kotlin 元数据低于 2.1；1.15.2 用 Kotlin 1.8.21 编译。

## 并发与 Flow

- 每个实体类型对应一个 `SingleCacheStore`，两种模式共用。
- 同一文件在进程内只能有一个活跃的 DataStore，所以 `id` 重复时必须立即报错。
- `SingleCacheStore` 按使用者计数：计数归零且没有创建过内存缓存实例时，取消 DataStore 的 scope 关闭它。
- 重新创建 DataStore 前必须等旧的关闭完成。
- 关闭完成不能用 `Job.join()` 判断：Job 完成后才执行 DataStore 释放文件的回调，`join()` 可能先返回。
- 关闭完成的信号由 DataStore 创建后注册的 `invokeOnCompletion` 回调发出，回调按注册顺序执行。
- `flow` 的整个收集过程、每次 `get()` 和 `update` 都算一个使用者。
- `update` 在进程内 `Mutex` 加跨进程文件锁内完成读改写，跨进程原子。
- 库会在 Java 和 Kotlin 混用的项目中使用，怎么调用都不能死锁，比如主线程 `runBlocking` 同步读写。
- 加锁和等锁都不能发生在调用方的上下文中：`get()` 和 `update` 整体切到 `Dispatchers.IO`，`flow` 的上游用 `flowOn(Dispatchers.IO)`。
- `get()` 直接取 DataStore `data` 的首个值，不能用 `flow.first()`：`flowOn` 让上游在首个值之后继续执行，每次读取都会启动 DataStore 的文件监听。
- 不能只在 `transform` 里切到 IO：DataStore 会先回到调用方的调度器才执行 `transform`。
- `block` 在锁内执行，耗时的 `block` 会让本进程和其他进程的 `update` 一起等待。
- DataStore 总有一个迁移任务，初始化读取要拿排他锁，所以进程内首次读取、仅磁盘缓存重新打开后的读取也会等其他进程的 `block`。
- 在 `block` 里交叉更新不同的缓存会互相等待而死锁，库不检测。
- 在 `block` 里读取其他缓存，多个进程交叉读取时内核会检测到文件锁死锁，一方重试约 82 秒后读取失败，读到默认值。
- 不要为此去掉初始化的排他锁：不加锁读取可能把旧数据记成最新版本号，直到下一次写入都不刷新。
- `flow` 在有订阅者时才监听文件。
- 没有订阅者时，`get()` 靠共享版本号判断内存值是否过期。
- 跨进程只保证最终一致：其他进程的写入通过 `FileObserver` 的 `MOVED_TO` 事件异步通知。
- 不支持运行中删除数据目录，审查时不要列为缺陷。
- 版本号是 `Int`，同一缓存累计写入约 21 亿次后回绕为负数，跨越时仍在运行的 DataStore 的订阅者收不到新值；概率极低，保持上游逻辑，审查时不要列为缺陷。
- 同一目录的文件监听共用一个 `FileObserver`，注册时目录不存在则监听失效，这是上游 `MulticastFileObserver` 的行为。
- 读取失败的 `flow` 只在数据目录存在时注册文件监听；目录不存在时每 10 秒重读一次。
- 计算缓存文件路径失败（如 API 23 及以下 `filesDir` 返回 null）也按目录不存在处理。

修改读取、版本号、订阅或 DataStore 生命周期逻辑时，两种模式都要测试：多个订阅者、快速连续更新、并发更新、跨进程更新通知、无订阅者时读到其他进程的写入。

## 异常模型

- 读写失败交给 `ExceptionHandler`，然后降级：`update` 返回 `false`，`flow` 保留当前值，首次读取失败发射默认值。
- 解码失败视为无缓存，序列化器不抛 `CorruptionException`。
- 解码失败返回 `CacheState.Undecodable`，不能用 `Absent`：否则 `update { null }` 时新旧值相等，DataStore 不写盘，文件删不掉。
- `update` 的 `block` 抛出的异常原样抛给调用方，不经过 `ExceptionHandler`。
- `block` 修改传入的值时，只有 hashCode 随之变化才会被 DataStore 检查到，`update` 返回 `false`。
- 没有缓存时传给 `block` 的是实例的 `defaultCache`，修改会一直留在这个实例上，检查不到。
- hashCode 和 equals 基于对象身份的类，修改后返回同一个对象时新旧值相等，不写盘，`update` 返回 `true`。
- 缺少注解、实体类带泛型参数、`id` 非法或重复时，取得实例立即抛 `IllegalArgumentException`。
- `id` 重复、内部配置错误和所有 JVM `Error` 必须继续抛出，不能降级。
- DataStore 的 scope 带 `CoroutineExceptionHandler`，内部协程没有处理的异常交给 `ExceptionHandler`，不能让进程崩溃。
- 接入方的 `ExceptionHandler` 自己抛出的异常不拦截，在 DataStore 内部协程里会让进程崩溃；公开文档已要求回调不抛异常。
- 订阅期间收到文件变化后拿锁失败，异常交给 `ExceptionHandler`，文件监听继续。
- 拿锁失败的那次变化不重试，等下一次文件事件、本进程的 `get()` 或 `update` 才读到。

## 文件格式

- 数据文件：`filesDir/sd.lib.singlecache/<md5(id)>.json`，内容是 Moshi 编码的 JSON；空文件表示无缓存。
- 编码时写出 null，解码用 moshi-kotlin 标准的 `KotlinJsonAdapterFactory`，区分显式 null 和字段缺失。
- 不要换回把显式 null 当作缺失的适配器（如 fMoshi）：可空字段的默认值不是 null 时，写入 null 会读回默认值。
- DataStore 在同目录创建 `<文件名>.lock`、`<文件名>.version`、`<文件名>.tmp`。
- 修改目录、文件名、编码或版本文件格式都是持久化兼容性变更，需要迁移说明和测试。

## 构建与验证

在仓库根目录执行：

```bash
./gradlew :lib:assembleRelease       # 构建发布 AAR
./gradlew :app:assembleDebug         # 构建示例 APK
./gradlew lint                       # 执行 Android Lint
./gradlew :app:connectedAndroidTest  # 在设备或模拟器运行完整测试
```

运行单个类时追加：

```bash
-Pandroid.testInstrumentationRunnerArguments.class=com.sd.demo.singlecache.SingleCacheTest
```

测试使用 AndroidX JUnit4 和 Turbine：

| 测试类 | 覆盖范围 |
|---|---|
| `SingleCacheTest` | 读写、删除、挂起的 block、阻塞调用方线程时不死锁、新实例共用 DataStore、Flow、并发更新、解码失败、非法 UTF-8、删除无法解码的缓存、替换文件失败不缓存新值、null 值读写、不可空字段读到 null、读取失败、锁被占用时恢复读取、数据目录创建失败后恢复、计算缓存文件路径失败后恢复、并发取消订阅后的订阅计数和文件监听、读取不启动文件监听、订阅期间拿锁失败不崩溃且继续监听、非法 UTF-16、配置错误、泛型实体 |
| `DiskSingleCacheTest` | 仅磁盘缓存：实例和默认值、释放后重新读盘、Flow、并发更新、与内存缓存混用、删除无法解码的缓存、替换文件失败不缓存新值、null 值读写、读取失败后的写入、锁被占用时恢复读取、数据目录创建失败后恢复、计算缓存文件路径失败后恢复、并发取消订阅后的订阅计数和文件监听、读取不启动文件监听、订阅期间拿锁失败不崩溃且继续监听、阻塞调用方线程时不死锁 |
| `MultiProcessSingleCacheTest` | 两种模式的跨进程并发更新和更新通知、无订阅者读取 |

- 文件监听和 IO 用的是真实时间，所以使用 `runBlocking`、`TEST_TIMEOUT`（15 秒）和 `awaitItemUntil`，不要换成虚拟时间的 `runTest`。
- 新增测试模型使用唯一的 `id`。
- 数据目录的测试会清空目录，测试不能依赖其他测试留下的文件。
- 清空目录后，之前打开过的 DataStore 的版本号会失效，所以每个测试使用自己的模型，不跨测试复用。
- 用到远端进程的测试只放在 `MultiProcessSingleCacheTest`，它的模型会被远端进程一直持有。
- 仅磁盘缓存的测试不能和内存缓存共用模型：进程内创建过某个类型的内存缓存后，这个类型的 DataStore 不再关闭。
- 测试结束时必须结束所有订阅，否则检查文件监听数量的测试会失败。

## 编码与提交

- 遵循 Kotlin 官方风格，两空格缩进，多行参数保留尾逗号；`datastore/` 目录保留上游的四空格风格。
- 公开行为添加 KDoc，实现细节优先用 `internal`/`private`。
- README、CHANGELOG 和公开 API 的 KDoc 只写使用者关心的行为和约束，不写锁、版本号、文件监听、DataStore 等内部实现；内部细节记录在 AGENTS.md。
- 依赖版本统一放入版本目录。
- 修改 `@SingleCacheEntity` 或反射模型时，同时检查 `consumer-rules.pro`。
- 混淆规则只保留实体类本身，嵌套类和枚举由接入方保留（见 README）。
- 示例 app 没开混淆，测试覆盖不到混淆相关的问题。
- 提交信息格式为 `type(scope): 中文描述`，例如 `fix(lib): 读取失败时保留当前值`。
- 一个提交只处理一个主题。
- 公开 API 或磁盘格式变化，必须在 `CHANGELOG.md` 写迁移说明。
- 发布前同步 `VERSION_NAME`、变更日志和 Maven 配置。
- 不要提交签名密钥、仓库凭据、`local.properties` 或 `build/` 产物。
