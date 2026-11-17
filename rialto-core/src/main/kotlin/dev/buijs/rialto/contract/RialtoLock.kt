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

import com.fasterxml.jackson.databind.JsonNode
import java.nio.channels.FileChannel
import java.nio.channels.OverlappingFileLockException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.name
import kotlin.io.path.readText

/**
 * `rialto.lock` next to a contract: the SHA-256 of every remote source (K4) under `sources`. Each run changes it
 * through [update]: under a file lock shared with rialto-dart, it reads the lock again, changes it and replaces it
 * atomically.
 */
class RialtoLock(
    val file: Path,
) {
    /** A lock that exists but is no lock: the message is the reason (K4.5). */
    class Unreadable(
        reason: String,
    ) : java.io.IOException(reason)

    /** What the lock holds: the hash of every remote source, by URL. */
    data class Content(
        val sources: Map<String, String> = emptyMap(),
    )

    /** The content, empty when there is no lock; throws when the file cannot be read. */
    fun read(): Content {
        if (!file.exists()) return Content()
        val root = ContractMapper.yaml.readTree(file.readText()) ?: return Content()
        if (root.isMissingNode || root.isNull) return Content()
        if (!root.isObject) throw Unreadable("not a mapping")

        return Content(sources(root.path("sources")))
    }

    /**
     * Reads the lock, applies [change] and writes the result when it differs; returns the new content. No
     * other writer, in this process or another (rialto-dart too), runs in between. Throws when the lock
     * cannot be read.
     */
    fun update(change: (Content) -> Content): Content =
        exclusive(file) {
            val before = read()
            val after = change(before)
            if (after != before && (file.exists() || after != Content())) write(after)
            after
        }

    /** Writes [content] atomically: to a temporary file next to the lock, then renamed over it. */
    private fun write(content: Content) {
        val temporary = Files.createTempFile(file.toAbsolutePath().parent, ".${file.name}", ".tmp")
        try {
            Files.writeString(temporary, text(content))
            try {
                Files.move(
                    temporary,
                    file,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (e: AtomicMoveNotSupportedException) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    private fun sources(node: JsonNode): Map<String, String> {
        if (node.isMissingNode || node.isNull) return emptyMap()
        if (!node.isObject) throw Unreadable("sources is not a mapping")

        return node.properties().associate { (url, hash) ->
            if (!hash.isTextual) throw Unreadable("$url is not a URL with a hash")

            url to hash.asText()
        }
    }

    companion object {
        /**
         * The text of [content]: `sources`, sorted by URL. rialto-dart writes the same bytes.
         */
        fun text(content: Content): String =
            buildString {
                appendLine(
                    "# Written by Rialto: the SHA-256 of every remote source of the contract (K4). Commit this file;",
                )
                appendLine("# `rialto sources update` accepts new versions.")
                if (content.sources.isEmpty()) appendLine("sources: {}") else appendLine("sources:")
                content.sources.toSortedMap().forEach { (url, hash) ->
                    appendLine("  \"$url\": \"$hash\"")
                }
            }

        private val inProcess = Any()

        /**
         * Runs [action] holding the lock of [file]: an exclusive lock on `<cache>/locks/<sha256 of its real
         * path>.lck`, which rialto-dart takes as well. The lock lives in the cache, not next to the file, so
         * no lock file appears in a source tree; and not on the file itself, which the atomic rename replaces.
         */
        fun <T> exclusive(
            file: Path,
            action: () -> T,
        ): T {
            val absolute = file.toAbsolutePath()
            val real =
                absolute.parent
                    .let { if (it.exists()) it.toRealPath() else it }
                    .resolve(absolute.name)
            val locks = RemoteSources.cacheDir().resolve("locks")
            val channel =
                try {
                    FileChannel.open(
                        locks.createDirectories().resolve(
                            RemoteSources.sha256(real.toString().toByteArray()) + ".lck",
                        ),
                        StandardOpenOption.CREATE,
                        StandardOpenOption.WRITE,
                    )
                } catch (e: java.io.IOException) {
                    // A cache that cannot be written leaves the atomic rename as the only protection.
                    return synchronized(inProcess, action)
                }

            return channel.use {
                synchronized(inProcess) {
                    // Another class loader in this JVM (a second plugin class path) may hold it already.
                    var lock = null as java.nio.channels.FileLock?
                    while (lock == null) {
                        try {
                            lock = channel.lock()
                        } catch (e: OverlappingFileLockException) {
                            Thread.sleep(LOCK_RETRY_MILLIS)
                        }
                    }

                    try {
                        action()
                    } finally {
                        lock.release()
                    }
                }
            }
        }

        private const val LOCK_RETRY_MILLIS = 20L
    }
}
