# Changelog

## Unreleased

### ✨ Features

- 首个版本：单值缓存 `SingleCache`，支持多进程读写
- 多个进程同时 `update` 不会丢失修改
- 支持仅磁盘缓存：`singleCache(memoryCache = false)` 没有订阅和进行中的 `update` 时释放内存中的值
- `singleCache` 每次调用返回新实例，同一类型的实例共用同一份缓存
