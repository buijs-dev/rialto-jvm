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

import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.nio.channels.FileChannel
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.io.path.createDirectories
import kotlin.io.path.createFile
import kotlin.io.path.exists
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.readText
import kotlin.io.path.writeText

/** `rialto.lock`: reading it, changing it under the shared file lock, and writing it atomically (K4.5). */
class RialtoLockTest {
    @TempDir
    lateinit var work: Path

    @TempDir
    lateinit var cache: Path

    private val file: Path get() = work.resolve(RemoteSources.LOCK_FILE)

    private val lock: RialtoLock get() = RialtoLock(file)

    @BeforeEach
    fun setUp() {
        System.setProperty("rialto.cache", cache.toString())
    }

    @AfterEach
    fun tearDown() {
        System.clearProperty("rialto.cache")
    }

    @ParameterizedTest(name = "[{0}]")
    @CsvSource(value = ["''", "'# only a comment'", "'~'", "'sources:'", "'sources: ~'"])
    fun `a lock without sources is an empty lock`(text: String) {
        file.writeText(text)
        assertThat(lock.read()).isEqualTo(RialtoLock.Content())
    }

    @Test
    fun `no lock is an empty lock`() {
        assertThat(lock.read()).isEqualTo(RialtoLock.Content())
        assertThat(lock.file).isEqualTo(file)
    }

    @ParameterizedTest(name = "[{0}]")
    @CsvSource(
        delimiter = '|',
        value = [
            "- a                 | not a mapping",
            "sources: [a]        | sources is not a mapping",
            "sources: {a: 1}     | a is not a URL with a hash",
            "sources: [unclosed  | not valid YAML: ",
        ],
    )
    fun `a lock that is no lock is unreadable, with the reason`(
        text: String,
        reason: String,
    ) {
        file.writeText(text)
        assertThatThrownBy {
            lock.read()
        }.isInstanceOf(RialtoLock.Unreadable::class.java).hasMessageStartingWith(reason)
    }

    @Test
    fun `a directory, or a file the system cannot read, is unreadable with the reason of the system`() {
        file.createDirectories()
        assertThatThrownBy {
            lock.read()
        }.isInstanceOf(RialtoLock.Unreadable::class.java).hasMessage("it is a directory")

        val other = RialtoLock(work.resolve("other.lock").also { it.writeText("sources: {}") })
        mockkStatic("kotlin.io.path.PathsKt__PathReadWriteKt")
        try {
            every { other.file.readText() } throws
                java.nio.file.AccessDeniedException(other.file.toString())
            assertThatThrownBy {
                other.read()
            }.isInstanceOf(RialtoLock.Unreadable::class.java).hasMessage("Permission denied")
        } finally {
            unmockkStatic("kotlin.io.path.PathsKt__PathReadWriteKt")
        }
    }

    @Test
    fun `an update writes only a change, and never an empty lock where there was none`() {
        assertThat(lock.update { it }).isEqualTo(RialtoLock.Content())
        assertThat(file.exists()).isFalse()

        val content = RialtoLock.Content(mapOf("https://b" to "2", "https://a" to "1"))
        assertThat(lock.update { content }).isEqualTo(content)
        assertThat(
            file.readText(),
        ).endsWith("sources:\n  \"https://a\": \"1\"\n  \"https://b\": \"2\"\n")

        val written = Files.getLastModifiedTime(file)
        lock.update { it }

        assertThat(Files.getLastModifiedTime(file)).isEqualTo(written)

        lock.update { RialtoLock.Content() }

        assertThat(file.readText()).endsWith("sources: {}\n")
        assertThat(work.listDirectoryEntries()).containsExactly(file)
    }

    @Test
    fun `the text of a lock is the same bytes for the same content`() {
        assertThat(RialtoLock.text(RialtoLock.Content())).isEqualTo(
            "# Written by Rialto: the SHA-256 of every remote source of the contract (K4). " +
                "Commit this file;\n" +
                "# `rialto sources update` accepts new versions.\n" +
                "sources: {}\n",
        )
    }

    @Test
    fun `without an atomic rename the lock is replaced by a plain one`() {
        val original = RialtoLock.move
        val moves = mutableListOf<List<Any>>()
        RialtoLock.move = { from, to, options ->
            moves += options.toList()
            if (StandardCopyOption.ATOMIC_MOVE in options) {
                throw AtomicMoveNotSupportedException(null, null, "no")
            }

            original(from, to, options)
        }

        try {
            lock.update { RialtoLock.Content(mapOf("https://a" to "1")) }
        } finally {
            RialtoLock.move = original
        }

        assertThat(moves).containsExactly(
            listOf(StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING),
            listOf(StandardCopyOption.REPLACE_EXISTING),
        )
        assertThat(lock.read().sources).containsEntry("https://a", "1")
        assertThat(work.listDirectoryEntries()).containsExactly(file)
    }

    @Test
    fun `a lock next to a directory that does not exist yet is taken by its path`() {
        val missing = work.resolve("not/yet/${RemoteSources.LOCK_FILE}")
        assertThat(RialtoLock.exclusive(missing) { "ran" }).isEqualTo("ran")
    }

    @Test
    fun `a cache that cannot be written leaves the atomic rename as the only protection`() {
        val notADirectory = work.resolve("cache").createFile()
        System.setProperty("rialto.cache", notADirectory.toString())

        assertThat(RialtoLock.exclusive(file) { "ran" }).isEqualTo("ran")
    }

    @Test
    fun `a lock another holder has in this JVM is waited for`() {
        val real = work.toRealPath().resolve(RemoteSources.LOCK_FILE)
        val lockFile =
            cache
                .resolve("locks")
                .createDirectories()
                .resolve(RialtoCache.sha256(real.toString().toByteArray()) + ".lck")

        FileChannel
            .open(
                lockFile,
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE,
            ).use { channel ->
                // Another class loader of this JVM: its lock overlaps, so taking it throws until it is released.
                val held = channel.lock()
                val waiting = CompletableFuture.supplyAsync { RialtoLock.exclusive(file) { "ran" } }

                Thread.sleep(100)
                assertThat(waiting).isNotDone()
                held.release()
                assertThat(waiting.get(5, TimeUnit.SECONDS)).isEqualTo("ran")
            }
    }

    @Test
    fun `the message of a file error leaves out the path`() {
        assertThat(
            RialtoLock.osMessage(java.nio.file.AccessDeniedException("/x")),
        ).isEqualTo("Permission denied")
        assertThat(
            RialtoLock.osMessage(java.nio.file.NoSuchFileException("/x")),
        ).isEqualTo("No such file or directory")
    }
}
