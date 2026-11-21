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

package dev.buijs.rialto.contract

import tools.jackson.core.JacksonException
import tools.jackson.databind.JsonNode
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.file.AccessDeniedException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.CopyOption
import java.nio.file.FileSystemException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.name
import kotlin.io.path.readText

/**
 * `rialto.lock` next to a contract: the SHA-256 of every remote source (K4) under `sources`. Each run changes it
 * through [update]: under a file lock, it reads the lock again, changes it and replaces it atomically.
 *
 * The lock is committed with the contract and is the record of which upstream versions were accepted. Builds
 * may run side by side (Gradle and the command line on the same checkout), so a change never starts from a
 * stale copy, and a reader never sees a half-written file. [RemoteSources] decides what to pin; this class only
 * reads and writes the file safely. From slice 10 it also holds the generator pins.
 */
class RialtoLock(
    val file: Path,
) {
    /** A lock that exists but is no lock: the message is the reason (K4.5). */
    class Unreadable(
        reason: String,
    ) : IOException(reason)

    /** What the lock holds: the hash of every remote source, by URL. */
    data class Content(
        val sources: Map<String, String> = emptyMap(),
    )

    /**
     * The content, empty when there is no lock. Every reason the file cannot be read, from the file system or
     * from its content, is thrown as [Unreadable], with a message an author can act on.
     */
    fun read(): Content {
        if (Files.isDirectory(file)) {
            throw Unreadable("it is a directory")
        }

        if (!file.exists()) {
            return Content()
        }

        val root =
            try {
                ContractMapper.yaml.readTree(file.readText())
            } catch (e: JacksonException) {
                throw Unreadable("not valid YAML: ${e.originalMessage}")
            } catch (e: IOException) {
                throw Unreadable(osMessage(e))
            }

        // An empty document, or one with only comments, is the missing node; `~` is null.
        if (root.isMissingNode || root.isNull) {
            return Content()
        }

        if (!root.isObject) {
            throw Unreadable("not a mapping")
        }

        return Content(sources(root.path("sources")))
    }

    /**
     * Reads the lock, applies [change] and writes the result when it differs; returns the new content. No
     * other writer, in this process or another, runs in between. Throws when the lock cannot be read.
     */
    fun update(change: (Content) -> Content): Content =
        exclusive(file) {
            val before = read()
            val after = change(before)
            // Without a lock, before is empty: an empty result is no change and writes no lock.
            if (after != before) {
                write(after)
            }

            after
        }

    /** Writes [content] atomically: to a temporary file next to the lock, then renamed over it. */
    private fun write(content: Content) {
        val temporary = Files.createTempFile(file.toAbsolutePath().parent, ".${file.name}", ".tmp")
        try {
            Files.writeString(temporary, text(content))
            try {
                move(
                    temporary,
                    file,
                    arrayOf(StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING),
                )
            } catch (e: AtomicMoveNotSupportedException) {
                move(temporary, file, arrayOf(StandardCopyOption.REPLACE_EXISTING))
            }
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    private fun sources(node: JsonNode): Map<String, String> {
        if (node.isMissingNode || node.isNull) {
            return emptyMap()
        }

        if (!node.isObject) {
            throw Unreadable("sources is not a mapping")
        }

        return node.properties().associate { (url, hash) ->
            if (!hash.isString) {
                throw Unreadable("$url is not a URL with a hash")
            }

            url to hash.asString()
        }
    }

    companion object {
        /**
         * The text of [content]: `sources`, sorted by URL, so the same content always gives the same bytes.
         */
        fun text(content: Content): String =
            buildString {
                appendLine(
                    "# Written by Rialto: the SHA-256 of every remote source of the contract (K4). Commit this file;",
                )
                appendLine("# `rialto sources update` accepts new versions.")
                if (content.sources.isEmpty()) {
                    appendLine("sources: {}")
                } else {
                    appendLine("sources:")
                }

                content.sources.toSortedMap().forEach { (url, hash) ->
                    appendLine("  \"$url\": \"$hash\"")
                }
            }

        private val inProcess = Any()

        /** Moves a file; replaceable in tests, as no file system of the JDK lacks an atomic move. */
        internal var move: (Path, Path, Array<out CopyOption>) -> Unit = { from, to, options ->
            Files.move(from, to, *options)
        }

        /**
         * Runs [action] holding the lock of [file]: an exclusive lock on `<cache>/locks/<sha256 of its real
         * path>.lck`, which every run on that file takes. The lock lives in the cache, not next to the file, so
         * no lock file appears in a source tree; and not on the file itself, which the atomic rename replaces.
         */
        fun <T> exclusive(
            file: Path,
            action: () -> T,
        ): T {
            // A cache that cannot be written leaves the atomic rename as the only protection.
            val channel = openLockFile(file) ?: return synchronized(inProcess, action)

            return channel.use {
                synchronized(inProcess) {
                    val lock = acquire(channel)
                    try {
                        action()
                    } finally {
                        lock.release()
                    }
                }
            }
        }

        /** The lock file of [file] in the cache, opened for writing; null when the cache cannot be written. */
        private fun openLockFile(file: Path): FileChannel? {
            val name = RialtoCache.sha256(realPath(file).toString().toByteArray()) + ".lck"
            return try {
                FileChannel.open(
                    RialtoCache.locks().createDirectories().resolve(name),
                    StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE,
                )
            } catch (e: IOException) {
                null
            }
        }

        /** [file] with the links in its directory resolved, so two paths to the same file take the same lock. */
        private fun realPath(file: Path): Path {
            val absolute = file.toAbsolutePath()
            val directory = absolute.parent
            val realDirectory = if (directory.exists()) directory.toRealPath() else directory
            return realDirectory.resolve(absolute.name)
        }

        /**
         * Waits for the lock on [channel]. When another class loader in this JVM holds it (a second plugin class
         * path), the JVM refuses instead of waiting; then this tries again a moment later.
         */
        private fun acquire(channel: FileChannel): FileLock {
            while (true) {
                try {
                    return channel.lock()
                } catch (e: OverlappingFileLockException) {
                    Thread.sleep(LOCK_RETRY_MILLIS)
                }
            }
        }

        /** The operating system's message for a failed file operation, without the path of the file. */
        internal fun osMessage(e: IOException): String =
            when (e) {
                is AccessDeniedException -> "Permission denied"
                is NoSuchFileException -> "No such file or directory"
                is FileSystemException -> e.reason ?: e.javaClass.simpleName
                else -> e.message ?: e.javaClass.simpleName
            }

        private const val LOCK_RETRY_MILLIS = 20L
    }
}
