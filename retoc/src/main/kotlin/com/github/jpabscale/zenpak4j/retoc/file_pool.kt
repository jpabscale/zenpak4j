// Ported from retoc (MIT) — Copyright (c) 2025 Truman Kilen and Archengius
// Rust: retoc/src/file_pool.rs:1
@file:Suppress("FunctionName", "PropertyName", "ClassName", "unused")

package com.github.jpabscale.zenpak4j.retoc

import java.io.RandomAccessFile
import java.nio.file.Path
import java.util.ArrayDeque
import java.util.concurrent.locks.Condition
import java.util.concurrent.locks.ReentrantLock

// Rust: retoc/src/file_pool.rs:6 PooledFileHandle
class PooledFileHandle(
    private var file: RandomAccessFile?,
    private val pool: FilePoolInner
) : AutoCloseable {

    // Rust: retoc/src/file_pool.rs:11 Drop -> return file handle to pool
    override fun close() {
        val f = file ?: return
        file = null
        pool.lock.lock()
        try {
            pool.state.available_files.addLast(f)
            pool.condvar.signal()
        } finally {
            pool.lock.unlock()
        }
    }

    // Rust: retoc/src/file_pool.rs:20 file()
    fun file(): RandomAccessFile = file ?: throw IllegalStateException("file already returned to pool")

    // Keep file accessible as property for parity with Kotlin use patterns
    fun get_file(): RandomAccessFile = file()
}

// Rust: retoc/src/file_pool.rs:26 PoolState
class PoolState(
    val available_files: ArrayDeque<RandomAccessFile> = ArrayDeque(),
    var active_count: Int = 0
)

// Rust: retoc/src/file_pool.rs:31 FilePoolInner
class FilePoolInner(
    val path: Path,
    val state: PoolState,
    val max_handles: Int,
    val lock: ReentrantLock,
    val condvar: Condition
)

// Rust: retoc/src/file_pool.rs:38 FilePool
class FilePool(
    val inner: FilePoolInner
) {

    // Kotlin addition mirroring Rust's Drop for FilePool (closes pooled handles deterministically)
    fun close() {
        inner.lock.lock()
        try {
            while (inner.state.available_files.isNotEmpty()) {
                inner.state.available_files.removeLast().close()
            }
        } finally {
            inner.lock.unlock()
        }
    }

    // Rust: retoc/src/file_pool.rs:43 FilePool::new
    companion object {
        fun new(path: Path, max_handles: Int): FilePool {
            // open file once to verify we can
            RandomAccessFile(path.toFile(), "r").use { }
            val lock = ReentrantLock()
            val condvar = lock.newCondition()
            val inner = FilePoolInner(
                path = path,
                state = PoolState(available_files = ArrayDeque(), active_count = 0),
                max_handles = max_handles,
                lock = lock,
                condvar = condvar
            )
            return FilePool(inner)
        }

        fun new(path: String, max_handles: Int): FilePool = new(Path.of(path), max_handles)
    }

    // Rust: retoc/src/file_pool.rs:58 acquire
    fun acquire(): PooledFileHandle {
        inner.lock.lock()
        try {
            while (true) {
                // grab an available handle if exists
                val file = inner.state.available_files.pollFirst()
                if (file != null) {
                    return PooledFileHandle(file, inner)
                }

                // open a new handle if max is not reached
                if (inner.state.active_count < inner.max_handles) {
                    val f = RandomAccessFile(inner.path.toFile(), "r")
                    inner.state.active_count += 1
                    return PooledFileHandle(f, inner)
                }

                // must wait for an available handle
                try {
                    inner.condvar.await()
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw java.io.IOException("interrupted while waiting for file handle", e)
                }
            }
        } finally {
            if (inner.lock.isHeldByCurrentThread) inner.lock.unlock()
        }
    }
}
