# updateData 替换文件失败后内存缓存留着没落盘的值

对应本库的修复：`DataStoreImpl.kt` 的 `writeData`，替换数据文件成功后才更新内存缓存。

## 提交方式

- 地址：https://issuetracker.google.com/issues/new?component=907884&template=1466542
- 勾选 Description 下方的 Markdown
- 附件：同目录的 `datastore-failed-write-repro.zip`，提交前先上传
- 已提交：https://issuetracker.google.com/issues/567175140

## Title

```
DataStore: a failed updateData leaves the unwritten value in the in-memory cache
```

## Description

````markdown
DataStore Component used: datastore-core (DataStoreFactory, MultiProcessDataStoreFactory)

DataStore Version used: 1.2.1 (DataStoreImpl.writeData on androidx-main has the same logic)

Devices/Android versions reproduced on: Android Emulator API 35 (sdk_gphone64_arm64), Android Emulator API 23 (arm64)

Sample project: attached `datastore-failed-write-repro.zip`. Run `./gradlew :repro:connectedAndroidTest` with an API 23+ device or emulator connected.

### Description

`DataStoreImpl.writeData` updates the in-memory cache inside the `writeScope` block:

```kotlin
storageConnection.writeScope {
    newVersion = coordinator.incrementAndGetVersion()
    writeData(newData)
    if (updateCache) {
        inMemoryCache.tryUpdate(Data(newData, newData.hashCode(), newVersion))
    }
}
```

`FileStorage.writeScope` runs the block first, which writes the scratch file, and only then renames the scratch file over the data file.
If the rename fails, `updateData` throws `IOException`, but the cache already holds the new value with the new version:

- Active collectors of `data` receive the new value although it was never written.
- `data` keeps returning the new value, because the cached version equals the shared version and the file is not read again.
- The next `updateData` reads the file under the lock, so its transform sees the old value. If that update writes nothing, the cache keeps the unwritten value.
- The unwritten value stays until the next successful write. After a restart the old value comes back.
- In multi-process mode, other processes see the version bump and read the old value, so processes disagree.

Only a failed rename triggers this. A failure while writing the scratch file happens before the cache update.

### Steps to reproduce

Run the instrumented tests below (the full file with imports is in the attached project).

- The first test uses `DataStoreFactory`, the second `MultiProcessDataStoreFactory`.
- The transform replaces the data file with a non-empty directory, so renaming the scratch file over it fails on all API levels.
- After the failure the test puts the old data file back, which is the state a failed rename leaves in practice.

```kotlin
@RunWith(AndroidJUnit4::class)
class FailedWriteTest {
    @Test
    fun singleProcessFailedWriteIsNotVisible(): Unit = runBlocking {
        val file = newFile("failed_write_single")
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val dataStore = DataStoreFactory.create(serializer = IntSerializer, scope = scope, produceFile = { file })
        assertFailedWriteIsNotVisible(file, dataStore)
        scope.coroutineContext.job.cancelAndJoin()
    }

    @Test
    fun multiProcessFailedWriteIsNotVisible(): Unit = runBlocking {
        val file = newFile("failed_write_multi")
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val dataStore = MultiProcessDataStoreFactory.create(serializer = IntSerializer, scope = scope, produceFile = { file })
        assertFailedWriteIsNotVisible(file, dataStore)
        scope.coroutineContext.job.cancelAndJoin()
    }
}

/** updateData fails because the data file cannot be replaced, the new value must not be visible afterwards */
private suspend fun assertFailedWriteIsNotVisible(file: File, dataStore: DataStore<Int>) = coroutineScope {
    dataStore.updateData { 1 }
    val collected = CopyOnWriteArrayList<Int>()
    val collector = launch(Dispatchers.IO) { dataStore.data.collect { collected += it } }
    withTimeout(5_000) { while (1 !in collected) delay(10) }

    val failure = runCatching {
        dataStore.updateData {
            // A non-empty directory cannot be replaced by the scratch file, so the rename fails
            check(file.delete())
            check(File(file, "blocker").mkdirs())
            2
        }
    }.exceptionOrNull()
    // A failed rename leaves the old data file in place, restore it that way
    check(file.deleteRecursively())
    file.writeText("1")

    // Give the collector time to receive anything emitted by the failed write
    delay(200)
    val dataAfterFailure = dataStore.data.first()
    val seenByNextUpdate = dataStore.updateData { it }
    val dataAfterNextUpdate = dataStore.data.first()
    collector.cancelAndJoin()

    val report = "updateDataThrew=${failure?.javaClass?.simpleName} collected=$collected dataAfterFailure=$dataAfterFailure " +
        "seenByNextUpdate=$seenByNextUpdate dataAfterNextUpdate=$dataAfterNextUpdate"
    assertTrue(
        report,
        failure is IOException && 2 !in collected && dataAfterFailure == 1 && seenByNextUpdate == 1 && dataAfterNextUpdate == 1,
    )
}

private fun newFile(name: String): File {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val dir = File(context.filesDir, name).apply {
        deleteRecursively()
        mkdirs()
    }
    return File(dir, "data")
}

private object IntSerializer : Serializer<Int> {
    override val defaultValue = 0
    override suspend fun readFrom(input: InputStream): Int = input.readBytes().decodeToString().toIntOrNull() ?: 0
    override suspend fun writeTo(t: Int, output: OutputStream) = output.write(t.toString().encodeToByteArray())
}
```

### Expected

Both tests pass: `updateData` throws, collectors never see 2, and `data` returns 1.

### Actual

Both tests failed on every run on both emulators, with the same output:

`updateDataThrew=IOException collected=[1, 2] dataAfterFailure=2 seenByNextUpdate=1 dataAfterNextUpdate=2`

### Suggested fix

Update the cache after `writeScope` returns, so it only happens when the rename succeeded.
The version is still incremented before the write, as the existing comment requires.
After a failed rename the cached version is behind the shared version, so the next read reloads the old value from disk.
With this patch applied to our fork of the 1.2.1 sources, both tests pass on both emulators. On androidx-main the same lines are inside the `trace` block.

```diff
         storageConnection.writeScope {
             newVersion = coordinator.incrementAndGetVersion()
             writeData(newData)
-            if (updateCache) {
-                inMemoryCache.tryUpdate(Data(newData, newData.hashCode(), newVersion))
-            }
         }
+        if (updateCache) {
+            inMemoryCache.tryUpdate(Data(newData, newData.hashCode(), newVersion))
+        }
```
````
