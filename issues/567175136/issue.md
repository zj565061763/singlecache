# 订阅者被取消时 DataStore 的订阅计数出错

对应本库的修复：`DataStoreImpl.kt` 的 `data`、`decrementCollector`，订阅计数加一成功才减一，减一不可取消。

## 提交方式

- 地址：https://issuetracker.google.com/issues/new?component=907884&template=1466542
- 勾选 Description 下方的 Markdown
- 附件：同目录的 `datastore-collector-counter-repro.zip`
- 已提交：https://issuetracker.google.com/issues/567175136

## Title

```
DataStore: collectorCounter drifts when collectors of data are cancelled concurrently
```

## Description

````markdown
DataStore Component used: datastore-core (MultiProcessDataStoreFactory)
DataStore Version used: 1.2.1 (DataStoreImpl on androidx-main has the same code)
Devices/Android versions reproduced on: Android Emulator API 35 (sdk_gphone64_arm64), Android Emulator API 23 (arm64)

Sample project: attached `datastore-collector-counter-repro.zip`. Run `./gradlew :repro:connectedAndroidTest` with an API 23+ device or emulator connected.

### Description

DataStoreImpl counts active collectors of `data` in `collectorCounter`, guarded by `collectorMutex`.
`incrementCollector()` runs in `onStart` and `decrementCollector()` in `onCompletion`.
`collectorJob`, which observes writes from other processes, starts when the counter becomes 1 and is cancelled when it drops to 0.

When the coroutine is cancelled, `Mutex.withLock` throws `CancellationException` without taking the lock whenever it has to wait, whether the cancellation happened before the call or during the wait.
The uncontended fast path does not check cancellation.
When collectors are cancelled while another collector holds `collectorMutex`, the counter drifts in two ways:

1. **Lost decrement.** A cancelled collector's `decrementCollector()` throws while waiting for the mutex, so the counter never gets back to 0.
   `collectorJob` and the file observer stay alive after all collectors are gone, and every write from another process still triggers a locked re-read.
   Cancelling several collectors at once, e.g. cancelling a scope that has several collectors, reliably hits this.
2. **Lost increment, executed decrement.** A collector cancelled while waiting in `incrementCollector()` never increments.
   `onCompletion` is downstream of `onStart`, so `decrementCollector()` still runs and succeeds if the mutex is free by then.
   The counter falls below the real number of collectors, so `collectorJob` is cancelled while collectors are still active and they stop receiving writes from other processes.

### Steps to reproduce

Run the instrumented tests below (full file with imports is in the attached project).
They read private fields of DataStoreImpl through reflection only to make the drift visible.
The second test holds `collectorMutex` itself to force the timing of case 2, standing in for another collector holding it.
Writes from another process are simulated by bumping the `.version` counter and renaming a new file over the data file.

```kotlin
@RunWith(AndroidJUnit4::class)
class CollectorCounterTest {
    /** Lost decrements: 64 collectors that have all incremented are cancelled together */
    @Test
    fun cancellingCollectorsTogether() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(context.filesDir, "collector_counter_together").apply { delete() }
        val dataStore = MultiProcessDataStoreFactory.create(serializer = IntSerializer, produceFile = { file })

        val longLived = launch(Dispatchers.IO) { dataStore.data.collect {} }
        withTimeout(5_000) { while (dataStore.collectorCounter() != 1) delay(10) }

        val counters = List(20) {
            val before = dataStore.collectorCounter()
            val group = launch { repeat(64) { launch(Dispatchers.IO) { dataStore.data.collect {} } } }
            withTimeout(5_000) { while (dataStore.collectorCounter() != before + 64) delay(10) }
            group.cancelAndJoin()
            dataStore.collectorCounter()
        }

        longLived.cancelAndJoin()
        val report = "counters=$counters final=${dataStore.collectorCounter()} " +
            "collectorJobActiveWithoutCollector=${dataStore.collectorJob()?.isActive == true}"
        assertTrue(report, counters.all { it == 1 } && dataStore.collectorCounter() == 0)
    }

    /**
     * Lost increment with an executed decrement. The test holds collectorMutex to stand in for another collector,
     * cancels a collector waiting in incrementCollector(), then releases the mutex.
     */
    @Test
    fun lostIncrementStopsCrossProcessUpdates() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(context.filesDir, "collector_counter_low").apply { delete() }
        File(file.path + ".version").delete()
        val dataStore = MultiProcessDataStoreFactory.create(serializer = IntSerializer, produceFile = { file })

        val latest = MutableStateFlow<Int?>(null)
        val longLived = launch(Dispatchers.IO) { dataStore.data.collect { latest.value = it } }
        withTimeout(5_000) { while (dataStore.collectorCounter() != 1) delay(10) }

        // Sanity check: a write from "another process" reaches the collector while the counter is right
        val receivedBefore = writeUntilReceived(file, 1, latest)

        val mutex = dataStore.collectorMutex()
        mutex.lock()
        val firstValue = CompletableDeferred<Unit>()
        val victim = launch(Dispatchers.IO) { dataStore.data.collect { firstValue.complete(Unit) } }
        firstValue.await()
        delay(200) // victim is now suspended in incrementCollector()
        victim.cancel()
        mutex.unlock()
        victim.join()

        val counterAfter = dataStore.collectorCounter()
        val jobActiveAfter = dataStore.collectorJob()?.isActive == true
        val receivedAfter = writeUntilReceived(file, 2, latest)

        longLived.cancelAndJoin()
        val report = "receivedBefore=$receivedBefore counterAfter=$counterAfter " +
            "collectorJobActiveAfter=$jobActiveAfter receivedAfter=$receivedAfter"
        assertTrue(report, receivedBefore && counterAfter == 1 && jobActiveAfter && receivedAfter)
    }
}

private object IntSerializer : Serializer<Int> {
    override val defaultValue = 0
    override suspend fun readFrom(input: InputStream): Int = input.readBytes().decodeToString().toIntOrNull() ?: 0
    override suspend fun writeTo(t: Int, output: OutputStream) = output.write(t.toString().encodeToByteArray())
}

// Private fields of DataStoreImpl, read only to make the drift visible
private fun DataStore<*>.collectorCounter(): Int =
    javaClass.getDeclaredField("collectorCounter").apply { isAccessible = true }.getInt(this)

private fun DataStore<*>.collectorJob(): Job? =
    javaClass.getDeclaredField("collectorJob").apply { isAccessible = true }.get(this) as Job?

private fun DataStore<*>.collectorMutex(): Mutex =
    javaClass.getDeclaredField("collectorMutex").apply { isAccessible = true }.get(this) as Mutex

/** Repeats the write because the file observer is registered asynchronously after the counter reaches 1 */
private suspend fun writeUntilReceived(file: File, value: Int, latest: MutableStateFlow<Int?>): Boolean =
    withTimeoutOrNull(5_000) {
        while (true) {
            writeFromAnotherProcess(file, value)
            if (withTimeoutOrNull(500) { latest.first { it == value } } != null) break
        }
    } != null

/** Writes like another process would: bump the shared version, then rename a new file over the data file */
private fun writeFromAnotherProcess(file: File, value: Int) {
    RandomAccessFile(File(file.path + ".version"), "rw").use { raf ->
        val buffer = raf.channel.map(FileChannel.MapMode.READ_WRITE, 0, Int.SIZE_BYTES.toLong())
        buffer.order(ByteOrder.nativeOrder())
        buffer.putInt(0, buffer.getInt(0) + 1)
    }
    val temp = File(file.path + ".other-${System.nanoTime()}")
    temp.writeText(value.toString())
    check(temp.renameTo(file))
}
```

### Expected

Both tests pass.

### Actual

Both tests failed on every run on both emulators:

- `cancellingCollectorsTogether`, API 35: `counters=[1, 2, 2, 2, 29, 32, 34, 34, 42, 44, 99, 102, 107, 107, 125, 131, 137, 141, 145, 149] final=148 collectorJobActiveWithoutCollector=true`
- `cancellingCollectorsTogether`, API 23: `counters=[1, 1, 4, 4, 8, 8, 8, 8, 8, 30, 41, 47, 47, 48, 49, 49, 49, 94, 98, 153] final=152 collectorJobActiveWithoutCollector=true`
- `lostIncrementStopsCrossProcessUpdates`, both: `receivedBefore=true counterAfter=0 collectorJobActiveAfter=false receivedAfter=false`

### Suggested fix

Decrement only if the increment succeeded, and make the decrement non-cancellable.
`collectorMutex` is only held for non-suspending work, so waiting for it under `NonCancellable` is bounded.
With this patch applied to our fork of the 1.2.1 sources, both tests pass on both emulators.

```diff
+        var incremented = false
         emitAll(
             inMemoryCache.flow
-                .onStart { incrementCollector() }
+                .onStart {
+                    incrementCollector()
+                    incremented = true
+                }
                 ...
-                .onCompletion { decrementCollector() }
+                .onCompletion { if (incremented) decrementCollector() }
         )

     private suspend fun decrementCollector() {
-        collectorMutex.withLock {
-            if (--collectorCounter == 0) {
-                collectorJob?.cancel()
-                collectorJob = null
+        withContext(NonCancellable) {
+            collectorMutex.withLock {
+                if (--collectorCounter == 0) {
+                    collectorJob?.cancel()
+                    collectorJob = null
+                }
             }
         }
     }
```
````
