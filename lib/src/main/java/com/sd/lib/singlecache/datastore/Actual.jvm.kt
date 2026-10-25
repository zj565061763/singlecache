/*
 * Copyright 2022 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

// 基于 AndroidX DataStore 1.2.1 修改

@file:RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)

package com.sd.lib.singlecache.datastore

import androidx.annotation.RestrictTo
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/** Common IOException mapped to java.io.Exception in jvm code. */
internal typealias IOException = java.io.IOException

internal class AtomicInt {
    private val delegate: AtomicInteger

    constructor(initialValue: Int = 0) {
        delegate = AtomicInteger(initialValue)
    }

    fun getAndIncrement(): Int = delegate.getAndIncrement()

    fun decrementAndGet(): Int = delegate.decrementAndGet()

    fun get(): Int = delegate.get()

    fun incrementAndGet(): Int = delegate.incrementAndGet()
}

internal class AtomicBoolean constructor(initialValue: Boolean) {
    private val delegate: java.util.concurrent.atomic.AtomicBoolean = AtomicBoolean(initialValue)

    fun get(): Boolean = delegate.get()

    fun set(value: Boolean) {
        delegate.set(value)
    }
}

internal fun ioDispatcher(): CoroutineDispatcher = Dispatchers.IO
