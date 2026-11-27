/**
 * Copyright (c) 2021 - 2026 Buijs Software
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
 * an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 */
package dev.buijs.rialto.core.utilities

import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.WRITE
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit.NANOSECONDS
import java.util.concurrent.locks.ReentrantLock
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.name

/**
 * Exclusive locks on files, across threads and processes, so that runs which change the same file take turns.
 * Runs on different files do not wait for each other.
 *
 * The lock of a file is `<locks>/<sha256 of its real path>.lck`, in a directory the caller names: not next to the
 * file, so no lock file appears in a source tree, and not on the file itself, which an atomic rename replaces. The
 * lock of a contract ([RialtoLock][dev.buijs.rialto.core.sources.RialtoLock]) keeps them in the cache of Rialto.
 */
internal object FileLocks {
    private const val RETRY_MILLIS = 20L

    /**
     * One lock per real path, so that threads of this class loader take turns per file, not across files, and never
     * open two channels on the same lock file. Entries stay: one small object per file this JVM ever locked.
     */
    private val inProcess = ConcurrentHashMap<Path, ReentrantLock>()

    /** The locks the current thread holds, by real path. */
    private val heldByThread = ThreadLocal.withInitial { mutableSetOf<Path>() }

    /** How long [exclusive] waits for a lock another run holds; replaceable in tests. */
    var timeout: Duration = Duration.ofSeconds(60)

    /** Another run held a lock longer than [timeout]. */
    class Timeout(
        message: String,
    ) : IOException(message)

    /**
     * Runs [action] holding the lock of [file]: first the lock of this process, then its lock file in [locks].
     * When [locks] cannot be written, only threads of this process take turns. The wait for other threads and other
     * processes together is bounded by [timeout].
     *
     * Throws [Timeout] when another run holds the lock longer than that, and [IllegalStateException] when this
     * thread already holds it: a change nested in another change of the same file would be overwritten by it.
     * Throws [InterruptedException] when the thread is interrupted while it waits, without taking the lock.
     * Whatever [action] throws passes through, after the lock is released.
     */
    fun <T> exclusive(
        file: Path,
        locks: Path,
        action: () -> T,
    ): T {
        val key = realPath(file)
        val held = heldByThread.get()
        check(held.add(key)) { "This thread already holds the lock of $file" }
        try {
            val deadline = System.nanoTime() + timeout.toNanos()
            val threadLock = inProcess.computeIfAbsent(key) { ReentrantLock() }
            if (!threadLock.tryLock(deadline - System.nanoTime(), NANOSECONDS)) {
                throw timedOut()
            }

            try {
                val channel = openLockFile(key, locks) ?: return action()
                return channel.use { acquire(it, deadline).use { action() } }
            } finally {
                threadLock.unlock()
            }
        } finally {
            held.remove(key)
        }
    }

    /** The lock file in [locks] of the file at [realPath], opened for writing; null when [locks] cannot be written. */
    private fun openLockFile(
        realPath: Path,
        locks: Path,
    ): FileChannel? =
        try {
            val name = sha256(realPath.toString().toByteArray()) + ".lck"
            FileChannel.open(locks.createDirectories().resolve(name), CREATE, WRITE)
        } catch (_: IOException) {
            null
        }

    /**
     * [file] with its links and the links of its directory resolved, so every path to the same file takes the
     * same lock; as it is when that fails, such as for a loop of links.
     */
    private fun realPath(file: Path): Path =
        try {
            val target = file.followLinks()
            val directory = target.parent
            val realDirectory = if (directory.exists()) directory.toRealPath() else directory
            realDirectory.resolve(target.name)
        } catch (_: IOException) {
            file.toAbsolutePath()
        }

    /**
     * Takes the lock on [channel], trying again a moment later while another run holds it: another process, or
     * another class loader in this JVM (a second plugin class path). Gives up at [deadline], in [System.nanoTime].
     */
    private fun acquire(
        channel: FileChannel,
        deadline: Long,
    ): FileLock {
        while (true) {
            val lock =
                try {
                    channel.tryLock()
                } catch (_: OverlappingFileLockException) {
                    null
                }

            if (lock != null) {
                return lock
            }

            if (System.nanoTime() >= deadline) {
                throw timedOut()
            }

            Thread.sleep(RETRY_MILLIS)
        }
    }

    private fun timedOut() = Timeout("another run held it for more than ${timeout.toSeconds()} s")

}
