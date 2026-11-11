# Changelog

## Unreleased

### ✨ Features

- 首个版本：单值缓存 `SingleCache`
- 默认只支持单进程，`@SingleCacheEntity(multiProcess = true)` 开启多进程读写
- 开启多进程后，多个进程同时 `update` 不会丢失修改
- 支持仅磁盘缓存：`singleCache(memoryCache = false)` 没有订阅和进行中的 `update` 时释放内存中的值
- `singleCache` 每次调用返回新实例，同一类型的实例共用同一份缓存
- Java 可用 `SingleCache.get(clazz, getDefault)`，也可显式传入 `memoryCache`
- 实体类型和默认值必须非空，实体内部的字段可以为 `null`；默认值为 `null` 时创建实例立即抛出 `IllegalArgumentException`
