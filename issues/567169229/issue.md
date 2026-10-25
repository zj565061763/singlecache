# MulticastFileObserver 注册后被取消时泄漏监听

对应本库的修复：`MulticastFileObserver.android.kt` 的 `observe(file)`，注册文件监听后发送首个事件失败时释放监听。

## 提交方式

- 地址：https://issuetracker.google.com/issues/new?component=907884&template=1466542
- 勾选 Description 下方的 Markdown
- 附件：同目录的 `datastore-file-observer-leak-repro.zip`
- 已提交：https://issuetracker.google.com/issues/567169229

## Title

```
DataStore: MulticastFileObserver leaks its listener when the collection is cancelled before awaitClose
```

## Description

````markdown
DataStore Component used: datastore-core (MultiProcessDataStoreFactory)
DataStore Version used: 1.2.1 (MulticastFileObserver.android.kt on androidx-main has the same code)
Devices/Android versions reproduced on: Android Emulator API 35 (sdk_gphone64_arm64), Android Emulator API 23 (arm64)

Sample project: attached `datastore-file-observer-leak-repro.zip`. Run `./gradlew :repro:connectedAndroidTest` with an API 23+ device or emulator connected.

### Description

`MulticastFileObserver.observe(file)` registers its listener on the directory observer, sends the initial `Unit`, and disposes the listener only in `awaitClose`:

```kotlin
val disposeListener = observe(file.parentFile!!, flowObserver)
send(Unit)
awaitClose { disposeListener.dispose() }
```

If the collection is cancelled before the producer reaches `awaitClose`, the channel is already cancelled when `send(Unit)` runs.
`send` throws, `awaitClose` is never reached, and the listener is never disposed.
The producer of a `channelFlow` starts with `CoroutineStart.ATOMIC`, so this also happens when the collection is cancelled before the producer has started.

In a multi-process DataStore, `collectorJob` collects `MultiProcessCoordinator.updateNotifications`, which is `MulticastFileObserver.observe(file)`.
`collectorJob` starts when the number of `data` collectors goes from 0 to 1 and is cancelled when it drops back to 0.
A short collection, such as `data.flowOn(Dispatchers.IO).first()`, can cancel it inside this window.

Consequences:

1. The listener and the directory's `FileObserver` are never released, even after the DataStore scope is cancelled. Leaked listeners accumulate.
2. `fileObservers` keeps the stale `MulticastFileObserver` for the directory.
   If the directory is deleted and recreated while no DataStore observes it, e.g. when an app clears its data, the inotify watch is gone.
   Later observers reuse this instance, and `startWatching()` is never called again because it still has listeners.
   No DataStore in that directory receives writes from other processes anymore.

### Steps to reproduce

Run the instrumented tests below (the full file with imports is in the attached project).

- `MulticastFileObserver` is internal, so the tests reach it through reflection, similar to what `MulticastFileObserverTest` does with `observe(file)` and `fileObservers`.
- The first two tests force the timing: they keep a single-threaded executor busy so the producer is queued, cancel the collection, then release the executor.
- The third test uses only the public API and hits the same race without any special setup. It is timing dependent.
- Writes from another process are simulated by bumping the `.version` counter and renaming a new file over the data file.

```kotlin
@RunWith(AndroidJUnit4::class)
class FileObserverLeakTest {
    /** The collection of observe(file) is cancelled before its producer starts, so send(Unit) throws */
    @Test
    fun listenerIsReleasedWhenCollectionIsCancelledBeforeProducerStarts() {
        val dir = newDirectory("observer_leak")
        leakListener(File(dir, "data"))
        assertEquals(0, listenerCount(dir))
    }

    /** A leaked listener keeps a stale FileObserver for the directory, which is reused after the directory is recreated */
    @Test
    fun writesFromAnotherProcessAreDeliveredAfterDirectoryIsRecreated(): Unit = runBlocking {
        val dir = newDirectory("observer_recreate")
        val file = File(dir, "data")
        leakListener(file)
        val leaked = listenerCount(dir)

        // The app clears the directory while no DataStore observes it, then uses it again
        dir.deleteRecursively()
        dir.mkdirs()
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val dataStore = MultiProcessDataStoreFactory.create(serializer = IntSerializer, scope = scope, produceFile = { file })
        val latest = MutableStateFlow<Int?>(null)
        val collector = launch(Dispatchers.IO) { dataStore.data.collect { latest.value = it } }
        // Wait until DataStore registers its own listener and handles the initial event,
        // otherwise that initial event alone makes it re-read the file
        withTimeout(5_000) { while (listenerCount(dir) != leaked + 1) delay(10) }
        delay(200)
        val received = writeUntilReceived(file, 1, latest)

        collector.cancelAndJoin()
        scope.coroutineContext.job.cancelAndJoin()
        assertTrue("leakedListeners=$leaked receivedAfterRecreate=$received", received)
    }

    /** Short collections through the public API hit the same race without any special setup, it is timing dependent */
    @Test
    fun listenersAreReleasedAfterShortCollections(): Unit = runBlocking {
        val dir = newDirectory("observer_short_collections")
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val dataStore = MultiProcessDataStoreFactory.create(serializer = IntSerializer, scope = scope, produceFile = { File(dir, "data") })

        // Nothing else collects data, so every call starts and stops observing the directory
        var calls = 0
        while (calls < 50_000) {
            dataStore.data.flowOn(Dispatchers.IO).first()
            calls++
            // Listeners still being registered or released settle quickly, a leaked one stays
            if (calls % 500 == 0 && listenerCount(dir) > 0) {
                delay(200)
                if (listenerCount(dir) > 0) break
            }
        }

        scope.coroutineContext.job.cancelAndJoin()
        val remaining = listenerCount(dir)
        assertEquals("calls=$calls remainingAfterDataStoreClosed=$remaining", 0, remaining)
    }
}

private fun newDirectory(name: String): File {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    return File(context.filesDir, name).apply {
        deleteRecursively()
        mkdirs()
    }
}

/** Cancels a collection of MulticastFileObserver.observe(file) after its producer is scheduled but before it runs */
private fun leakListener(file: File) {
    val executor = Executors.newSingleThreadExecutor()
    val release = CountDownLatch(1)
    // Occupy the only thread so the producer is queued but not started
    executor.execute { release.await() }
    val channel = observe(file).produceIn(CoroutineScope(executor.asCoroutineDispatcher()))
    channel.cancel()
    release.countDown()
    executor.shutdown()
    check(executor.awaitTermination(5, TimeUnit.SECONDS))
}

private object IntSerializer : Serializer<Int> {
    override val defaultValue = 0
    override suspend fun readFrom(input: InputStream): Int = input.readBytes().decodeToString().toIntOrNull() ?: 0
    override suspend fun writeTo(t: Int, output: OutputStream) = output.write(t.toString().encodeToByteArray())
}

// MulticastFileObserver is internal, so it is reached through reflection

@Suppress("UNCHECKED_CAST")
private fun observe(file: File): Flow<Unit> {
    val clazz = Class.forName("androidx.datastore.core.MulticastFileObserver")
    val companion = clazz.getDeclaredField("Companion").apply { isAccessible = true }.get(null)!!
    val method = companion.javaClass.getDeclaredMethod("observe", File::class.java).apply { isAccessible = true }
    return method.invoke(companion, file) as Flow<Unit>
}

/** Listeners registered for [dir], counted the same way as MulticastFileObserverTest checks fileObservers */
private fun listenerCount(dir: File): Int {
    val clazz = Class.forName("androidx.datastore.core.MulticastFileObserver")
    val lock = clazz.getDeclaredField("LOCK").apply { isAccessible = true }.get(null)!!
    val observers = clazz.getDeclaredField("fileObservers").apply { isAccessible = true }.get(null) as Map<*, *>
    return synchronized(lock) {
        val observer = observers[dir.canonicalPath] ?: return 0
        (clazz.getDeclaredField("delegates").apply { isAccessible = true }.get(observer) as List<*>).size
    }
}

/** Repeats the write because the file observer is registered asynchronously */
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

All three tests pass.

### Actual

- `listenerIsReleasedWhenCollectionIsCancelledBeforeProducerStarts` failed on every run on both emulators: `expected:<0> but was:<1>`
- `writesFromAnotherProcessAreDeliveredAfterDirectoryIsRecreated` failed on every run on both emulators: `leakedListeners=1 receivedAfterRecreate=false`
- `listenersAreReleasedAfterShortCollections` leaked a listener in 12 of 14 runs, after 500 to 46,000 calls, e.g. API 35: `calls=500 remainingAfterDataStoreClosed=2`, API 23: `calls=12000 remainingAfterDataStoreClosed=1`

### Suggested fix

Dispose the listener when `send(Unit)` fails.
With this patch applied to our fork of the 1.2.1 sources, all three tests pass on both emulators, and the third one runs all 50,000 calls without a leak.

```diff
             val disposeListener = observe(file.parentFile!!, flowObserver)
             ...
-            send(Unit)
+            try {
+                send(Unit)
+            } catch (e: Throwable) {
+                disposeListener.dispose()
+                throw e
+            }
             awaitClose { disposeListener.dispose() }
```
````
