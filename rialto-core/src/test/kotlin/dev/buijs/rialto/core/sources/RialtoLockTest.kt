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

package dev.buijs.rialto.core.sources

import dev.buijs.rialto.core.utilities.FileLocks
import dev.buijs.rialto.core.utilities.moveFile
import dev.buijs.rialto.core.utilities.osMessage
import dev.buijs.rialto.core.utilities.sha256
import dev.buijs.rialto.core.utilities.writeAtomically
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
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.io.path.createDirectories
import kotlin.io.path.createFile
import kotlin.io.path.exists
import kotlin.io.path.isSymbolicLink
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.io.path.writeText

/** The lock of a contract: its name and owner, reading it, changing it under the file lock (K4.5-K4.7). */
class RialtoLockTest {
    @TempDir
    lateinit var work: Path

    @TempDir
    lateinit var cache: Path

    private val file: Path get() = work.resolve(LOCK)

    private val lock: RialtoLock get() = RialtoLock(file, CONTRACT)

    /** [FileLocks.exclusive] with its lock files in the cache, as [RialtoLock] keeps them. */
    private fun <T> exclusive(
        file: Path,
        action: () -> T,
    ): T = FileLocks.exclusive(file, RialtoCache.locks(), action)

    private companion object {
        val A = sha256("a".toByteArray())
        val B = sha256("b".toByteArray())
        const val CONTRACT = "contract.rialto.yaml"
        const val LOCK = "contract.rialto.lock"
    }

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
        // given
        file.writeText(text)

        // expect
        assertThat(lock.read()).isEqualTo(RialtoLock.Content())
    }

    @Test
    fun `no lock is an empty lock`() {
        // expect
        assertThat(lock.read()).isEqualTo(RialtoLock.Content())
        assertThat(lock.file).isEqualTo(file)
    }

    @ParameterizedTest(name = "[{0}]")
    @CsvSource(
        delimiter = '|',
        value = [
            "- a                 | not a mapping",
            "sources: [a]        | sources is not a mapping",
            "sources: {a: 1}     | the hash of a is not a SHA-256 in lower-case hex",
            "sources: {a: abc}   | the hash of a is not a SHA-256 in lower-case hex",
            "sources: [unclosed  | not valid YAML: ",
        ],
    )
    fun `a lock that is no lock is unreadable, with the reason`(
        text: String,
        reason: String,
    ) {
        // given
        file.writeText(text)

        // expect
        assertThatThrownBy { lock.read() }
            .isInstanceOf(RialtoLock.Unreadable::class.java)
            .hasMessageStartingWith(reason)
    }

    @Test
    fun `a directory, or a file the system cannot read, is unreadable with the reason of the system`() {
        // given
        file.createDirectories()

        // expect
        assertThatThrownBy { lock.read() }
            .isInstanceOf(RialtoLock.Unreadable::class.java)
            .hasMessage("it is a directory")

        // given
        val other = RialtoLock(work.resolve("other.lock").also { it.writeText("sources: {}") }, CONTRACT)
        mockkStatic("kotlin.io.path.PathsKt__PathReadWriteKt")
        try {
            every { other.file.readText() } throws
                java.nio.file.AccessDeniedException(other.file.toString())

            // expect
            assertThatThrownBy { other.read() }
                .isInstanceOf(RialtoLock.Unreadable::class.java)
                .hasMessage("Permission denied")
        } finally {
            unmockkStatic("kotlin.io.path.PathsKt__PathReadWriteKt")
        }
    }

    @Test
    fun `an update writes only a change, and never an empty lock where there was none`() {
        // expect
        assertThat(lock.update { it }).isEqualTo(RialtoLock.Content())
        assertThat(file.exists()).isFalse()

        // given
        val content = RialtoLock.Content(mapOf("https://b" to B, "https://a" to A))

        // expect
        assertThat(lock.update { content }).isEqualTo(content)
        assertThat(file.readText()).endsWith("sources:\n  \"https://a\": \"$A\"\n  \"https://b\": \"$B\"\n")

        // given
        val written = Files.getLastModifiedTime(file)

        // when
        lock.update { it }

        // then
        assertThat(Files.getLastModifiedTime(file)).isEqualTo(written)

        // when
        lock.update { RialtoLock.Content() }

        // then
        assertThat(file.readText()).endsWith("sources: {}\n")
        assertThat(work.listDirectoryEntries()).containsExactly(file)
    }

    @Test
    fun `the text of a lock is the same bytes for the same content`() {
        // given
        val expected =
            "# Written by Rialto: the SHA-256 of every remote source of the contract (K4). " +
                "Commit this file;\n" +
                "# `rialto sources update` accepts new versions.\n" +
                "contract: \"$CONTRACT\"\n" +
                "sources: {}\n"

        // expect
        assertThat(RialtoLock.text(CONTRACT, RialtoLock.Content())).isEqualTo(expected)
        assertThat(RialtoLock.text("a: \"b\".yaml", RialtoLock.Content(mapOf("https://a" to A))))
            .contains("contract: \"a: \\\"b\\\".yaml\"\n")
            .endsWith("  \"https://a\": \"$A\"\n")
    }

    @ParameterizedTest(name = "[{0}]")
    @CsvSource(
        value = [
            "shipment.rialto.yaml, shipment.rialto.lock",
            "shipment.rialto.yml,  shipment.rialto.lock",
            "shipment.rialto.json, shipment.rialto.lock",
            "shipment.yaml,        shipment.rialto.lock",
            "shipment.json,        shipment.rialto.lock",
            "shipment,             shipment.rialto.lock",
            "shipment.rialto,      shipment.rialto.lock",
            "api.txt,              api.txt.rialto.lock",
            "a.yaml.json,          a.yaml.rialto.lock",
            ".rialto.yaml,         .rialto.lock",
        ],
    )
    fun `the lock is named after the root of the contract (K4_6)`(
        root: String,
        expected: String,
    ) {
        // expect
        assertThat(RialtoLock.fileName(root)).isEqualTo(expected)
    }

    @Test
    fun `the lock of a root lies next to it and belongs to it`() {
        // when
        val of = RialtoLock.of(work.resolve("contracts/shipment.rialto.yaml"))

        // then
        assertThat(of.file).isEqualTo(work.resolve("contracts/shipment.rialto.lock").toAbsolutePath())
        assertThat(of.contract).isEqualTo("shipment.rialto.yaml")
    }

    @ParameterizedTest(name = "[{0}]")
    @CsvSource(
        delimiter = '|',
        value = [
            "sources: {a: %s}                                 | it names no contract",
            "contract: other.rialto.yaml\\nsources: {a: %s}    | it belongs to other.rialto.yaml",
            "contract: Contract.rialto.yaml\\nsources: {}      | it belongs to Contract.rialto.yaml",
            "contract: [a]\\nsources: {}                       | contract is not a file name",
        ],
    )
    fun `a lock that names no contract, or another, is not the lock of this contract (K4_7)`(
        text: String,
        reason: String,
    ) {
        // given
        file.writeText(text.replace("\\n", "\n").format(A))

        // expect
        assertThatThrownBy { lock.read() }
            .isInstanceOf(RialtoLock.Unreadable::class.java)
            .hasMessage(reason)
    }

    @ParameterizedTest(name = "[{0}]")
    @CsvSource(value = ["'sources: {}'", "'contract: contract.rialto.yaml\\nsources: {a: %s}'"])
    fun `a lock without entries needs no contract, and one that names this contract is its lock`(text: String) {
        // given
        file.writeText(text.replace("\\n", "\n").format(A))

        // expect
        assertThat(lock.read().sources.keys).isSubsetOf("a")
    }

    @Test
    fun `without an atomic rename the lock is replaced by a plain one`() {
        // given
        val original = moveFile
        val moves = mutableListOf<List<Any>>()
        moveFile = { from, to, options ->
            moves += options.toList()
            if (StandardCopyOption.ATOMIC_MOVE in options) {
                throw AtomicMoveNotSupportedException(null, null, "no")
            }

            original(from, to, options)
        }

        // when
        try {
            lock.update { RialtoLock.Content(mapOf("https://a" to A)) }
        } finally {
            moveFile = original
        }

        // then
        assertThat(moves).containsExactly(
            listOf(StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING),
            listOf(StandardCopyOption.REPLACE_EXISTING),
        )
        assertThat(lock.read().sources).containsEntry("https://a", A)
        assertThat(work.listDirectoryEntries()).containsExactly(file)
    }

    @Test
    fun `a lock next to a directory that does not exist yet is taken by its path`() {
        // given
        val missing = work.resolve("not/yet/$LOCK")

        // expect
        assertThat(exclusive(missing) { "ran" }).isEqualTo("ran")
    }

    @Test
    fun `a cache that cannot be written leaves the atomic rename as the only protection`() {
        // given
        val notADirectory = work.resolve("cache").createFile()
        System.setProperty("rialto.cache", notADirectory.toString())

        // expect
        assertThat(exclusive(file) { "ran" }).isEqualTo("ran")
    }

    @Test
    fun `a lock another holder has in this JVM is waited for`() {
        // given
        val real = work.toRealPath().resolve(LOCK)
        val lockFile =
            cache
                .resolve("locks")
                .createDirectories()
                .resolve(sha256(real.toString().toByteArray()) + ".lck")

        FileChannel
            .open(
                lockFile,
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE,
            ).use { channel ->
                // Another class loader of this JVM: its lock overlaps, so taking it throws until it is released.
                val held = channel.lock()

                // when
                val waiting = CompletableFuture.supplyAsync { exclusive(file) { "ran" } }
                Thread.sleep(100)

                // then
                assertThat(waiting).isNotDone()

                // when
                held.release()

                // then
                assertThat(waiting.get(5, TimeUnit.SECONDS)).isEqualTo("ran")
            }
    }

    @Test
    fun `a lock another run holds too long is given up with a timeout`() {
        // given
        val original = FileLocks.timeout
        FileLocks.timeout = Duration.ofMillis(100)
        try {
            FileChannel.open(lockFileOf(file), StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { channel ->
                channel.lock().use {
                    // expect
                    assertThatThrownBy {
                        CompletableFuture.supplyAsync { exclusive(file) { "ran" } }.join()
                    }.hasCauseInstanceOf(FileLocks.Timeout::class.java)
                        .hasMessageContaining("another run held it for more than 0 s")
                }
            }
        } finally {
            FileLocks.timeout = original
        }
    }

    @Test
    fun `a change nested in a change of the same file is refused, of another file allowed`() {
        // expect
        assertThat(exclusive(file) { exclusive(work.resolve("other.lock")) { "ran" } })
            .isEqualTo("ran")
        assertThatThrownBy { exclusive(file) { exclusive(file) { "ran" } } }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageStartingWith("This thread already holds the lock of")

        // and the lock is free again afterwards
        assertThat(exclusive(file) { "ran" }).isEqualTo("ran")
    }

    @Test
    fun `a lock that is a symbolic link stays a link, and shares the lock of its target`() {
        // given
        val target = work.resolve("shared").createDirectories().resolve(LOCK)
        Files.createSymbolicLink(file, target)

        // when: the link dangles, then points to the file just written
        lock.update { RialtoLock.Content(mapOf("https://a" to A)) }
        lock.update { RialtoLock.Content(mapOf("https://a" to B)) }

        // then
        assertThat(file.isSymbolicLink()).isTrue()
        assertThat(RialtoLock(target, CONTRACT).read().sources).containsExactlyEntriesOf(mapOf("https://a" to B))
        assertThat(work.resolve("shared").listDirectoryEntries()).containsExactly(target)
        assertThatThrownBy { exclusive(file) { exclusive(target) { "ran" } } }
            .isInstanceOf(IllegalStateException::class.java)
    }

    @Test
    fun `a loop of links takes the lock by its path and cannot be written`() {
        // given
        val other = work.resolve("other.lock")
        Files.createSymbolicLink(file, other)
        Files.createSymbolicLink(other, file)

        // expect
        assertThat(exclusive(file) { "ran" }).isEqualTo("ran")
        assertThatThrownBy { file.writeAtomically("x".toByteArray()) }
            .isInstanceOf(java.nio.file.FileSystemException::class.java)
            .hasMessageContaining("Too many levels of symbolic links")
    }

    @Test
    fun `a thread of this process that holds a lock too long is given up with a timeout`() {
        // given
        val original = FileLocks.timeout
        val release = CountDownLatch(1)
        val holding = CountDownLatch(1)
        val holder =
            CompletableFuture.supplyAsync {
                exclusive(file) {
                    holding.countDown()
                    release.await()
                    "held"
                }
            }

        holding.await()
        FileLocks.timeout = Duration.ofMillis(100)

        // expect
        try {
            assertThatThrownBy {
                CompletableFuture.supplyAsync { exclusive(file) { "ran" } }.get(5, TimeUnit.SECONDS)
            }.hasCauseInstanceOf(FileLocks.Timeout::class.java)
        } finally {
            FileLocks.timeout = original
            release.countDown()
        }

        assertThat(holder.get(5, TimeUnit.SECONDS)).isEqualTo("held")
    }

    @Test
    fun `threads on different files do not wait for each other`() {
        // given
        val release = CountDownLatch(1)
        val holding = CountDownLatch(1)
        val holder =
            CompletableFuture.supplyAsync {
                exclusive(file) {
                    holding.countDown()
                    release.await()
                }
            }

        holding.await()

        // expect
        try {
            val other = CompletableFuture.supplyAsync { exclusive(work.resolve("other.lock")) { "ran" } }
            assertThat(other.get(5, TimeUnit.SECONDS)).isEqualTo("ran")
        } finally {
            release.countDown()
        }

        holder.get(5, TimeUnit.SECONDS)
    }

    private fun lockFileOf(file: Path): Path =
        cache
            .resolve("locks")
            .createDirectories()
            .resolve(
                sha256(
                    file
                        .toAbsolutePath()
                        .parent
                        .toRealPath()
                        .resolve(file.name)
                        .toString()
                        .toByteArray(),
                ) + ".lck",
            )

    @Test
    fun `the message of a file error leaves out the path`() {
        // expect
        assertThat(osMessage(java.nio.file.AccessDeniedException("/x"))).isEqualTo("Permission denied")
        assertThat(osMessage(java.nio.file.NoSuchFileException("/x"))).isEqualTo("No such file or directory")
    }

    @Test
    fun `rebase applies our changes onto the lock another run wrote`() {
        // given
        val base = mapOf("a" to "1", "b" to "1", "c" to "1", "d" to "1")
        val ours = mapOf("a" to "2", "b" to "1", "e" to "1") // a changed, c and d dropped, e added
        val theirs = mapOf("a" to "1", "b" to "3", "c" to "1", "d" to "3", "f" to "1") // b and d changed, f added

        // when
        val rebased = RialtoLock.rebase(base = base, ours = ours, theirs = theirs)

        // then
        assertThat(rebased.conflicts).isEmpty()
        assertThat(rebased.sources).isEqualTo(
            mapOf("a" to "2", "b" to "3", "d" to "3", "e" to "1", "f" to "1"),
        )
    }

    @Test
    fun `rebase reports a source both runs pinned to different hashes, and accepts the same hash`() {
        // when
        val rebased =
            RialtoLock.rebase(
                base = mapOf("a" to "1"),
                ours = mapOf("a" to "2", "b" to "1"),
                theirs = mapOf("a" to "3", "b" to "1"),
            )

        // then
        assertThat(rebased.conflicts).containsExactly("a")
    }

    @Test
    fun `a merge writes the change of a run onto the lock as it is now`() {
        // given: this run read a at A; another run has pinned b since
        lock.update { RialtoLock.Content(mapOf("https://a" to A, "https://b" to B)) }

        // when
        val merged = lock.merge(base = mapOf("https://a" to A), next = mapOf("https://a" to B))

        // then
        assertThat(merged).isEqualTo(RialtoLock.Merge.Merged)
        assertThat(lock.read().sources).isEqualTo(mapOf("https://a" to B, "https://b" to B))
    }

    @Test
    fun `a merge writes nothing when another run pinned the same source to another hash`() {
        // given: this run read a at A and pins it to B; another run has pinned it to C since
        val c = sha256("c".toByteArray())
        lock.update { RialtoLock.Content(mapOf("https://a" to c)) }
        val before = file.readText()

        // when
        val merged = lock.merge(base = mapOf("https://a" to A), next = mapOf("https://a" to B))

        // then
        assertThat(merged).isEqualTo(RialtoLock.Merge.Conflict(setOf("https://a")))
        assertThat(file.readText()).isEqualTo(before)
    }
}
