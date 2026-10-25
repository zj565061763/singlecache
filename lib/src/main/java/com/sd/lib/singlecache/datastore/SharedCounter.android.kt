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

// 基于 AndroidX DataStore 1.2.1 修改：用 Java mmap 代替 JNI 实现共享计数器

package com.sd.lib.singlecache.datastore

import com.sd.lib.singlecache.datastore.handlers.ReplaceFileCorruptionHandler
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

/**
 * 基于共享内存的版本计数器，多进程 DataStore 用它判断内存缓存是否过期。
 *
 * [incrementAndGetValue]只在持有跨进程排他文件锁时调用，所以不需要原子指令；
 * 不加锁的[getValue]最坏读到旧值，只会多一次加锁重读。
 *
 * 不能给 DataStore 传[ReplaceFileCorruptionHandler]：它处理解码失败时可能只持有共享锁，
 * 多个进程同时自增会丢失版本号。
 */
internal interface SharedCounter {
    fun getValue(): Int

    fun incrementAndGetValue(): Int

    private class RealSharedCounter(
        /** 映射版本文件的前4个字节，多个进程映射同一文件时共享这块内存 */
        private val buffer: MappedByteBuffer,
    ) : SharedCounter {
        override fun getValue(): Int {
            return buffer.getInt(0)
        }

        @Synchronized
        override fun incrementAndGetValue(): Int {
            val value = buffer.getInt(0) + 1
            buffer.putInt(0, value)
            return value
        }
    }

    companion object Factory {
        internal fun create(produceFile: () -> File): SharedCounter {
            val file = produceFile()
            // 关闭文件后映射仍然有效，文件不足4个字节时由map扩展
            val buffer = RandomAccessFile(file, "rw").use { raf ->
                raf.channel.map(FileChannel.MapMode.READ_WRITE, 0, Int.SIZE_BYTES.toLong())
            }
            buffer.order(ByteOrder.nativeOrder())
            return RealSharedCounter(buffer)
        }
    }
}
