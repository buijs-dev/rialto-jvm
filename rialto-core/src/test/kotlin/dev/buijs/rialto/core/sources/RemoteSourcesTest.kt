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

import dev.buijs.rialto.core.diagnostics.Code
import dev.buijs.rialto.core.diagnostics.Location
import dev.buijs.rialto.core.diagnostics.Outcome
import dev.buijs.rialto.core.diagnostics.valueOrFail
import dev.buijs.rialto.core.utilities.FileLocks
import dev.buijs.rialto.core.utilities.osMessage
import dev.buijs.rialto.core.utilities.sha256
import dev.buijs.rialto.core.validation.ContractValidator
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import org.junit.jupiter.params.provider.ValueSource
import java.net.URI
import java.nio.channels.FileChannel
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Duration
import java.util.concurrent.CompletableFuture
import kotlin.io.path.copyTo
import kotlin.io.path.createDirectories
import kotlin.io.path.readBytes
import kotlin.io.path.readText
import kotlin.io.path.writeText

class RemoteSourcesTest {
    @TempDir
    lateinit var work: Path

    @TempDir
    lateinit var cache: Path

    private val url = URL
    private val shipment = Path.of(javaClass.getResource("/shipment")!!.toURI())
    private var served: ByteArray? = null
    private var fetched = 0
    private lateinit var contract: Path
    private lateinit var originalFetch: (URI) -> ByteArray

    @BeforeEach
    fun setUp() {
        System.setProperty("rialto.cache", cache.toString())
        served = shipment.resolve("openapi.json").readBytes()
        originalFetch = RemoteSources.fetch
        RemoteSources.fetch =
            { uri: URI ->
                fetched++
                if (uri.toString() == url) served ?: error("offline") else error("404")
            }

        listOf(
            "openapi-v1.json",
            "asyncapi.json",
            "schema.sql",
        ).forEach { shipment.resolve(it).copyTo(work.resolve(it)) }

        contract = work.resolve("shipment.rialto.yaml")
        contract.writeText(
            shipment
                .resolve(
                    "shipment.rialto.yaml",
                ).readText()
                .replace("path: openapi.json", "path: \"$url\""),
        )
    }

    @AfterEach
    fun tearDown() {
        System.clearProperty("rialto.cache")
        RemoteSources.fetch = originalFetch
        work.toFile().setWritable(true)
    }

    private fun codes(remote: RemoteMode = RemoteMode.LOCKED) =
        ContractValidator.validate(contract, remote).diagnostics.map { it.code.id }

    private val lockFile: Path get() = RialtoLock.of(contract).file

    private fun lock() = lockFile.readText()

    @Test
    fun `fetches a new remote source, locks it and reads it offline from the cache (K4_1, K4_3)`() {
        // expect
        assertThat(codes()).isEmpty()
        assertThat(lock()).contains("\"$url\": \"${sha256(served!!)}\"")

        // given
        served = null

        // expect
        assertThat(codes(RemoteMode.OFFLINE)).isEmpty()
        assertThat(codes()).isEmpty()
    }

    @Test
    fun `rejects a changed source until it is updated on purpose (K4_2)`() {
        // given
        codes()
        val before = lock()
        cache.toFile().deleteRecursively()
        served = served!! + " ".toByteArray()

        // expect
        assertThat(codes()).containsExactly("RIALTO-S004")
        assertThat(lock()).isEqualTo(before)

        // when
        val update = RemoteSources.update(contract)

        // then
        assertThat(update.diagnostics).isEmpty()
        assertThat(update.valueOrFail()).singleElement().matches { it.url == url && it.after == sha256(served!!) }
        assertThat(codes()).isEmpty()
    }

    @Test
    fun `rejects a source that is neither cached nor fetchable, and plain http (K4_3, K4_4)`() {
        // expect
        assertThat(codes(RemoteMode.OFFLINE)).containsExactly("RIALTO-S003")

        // given
        served = null

        // expect
        assertThat(codes()).containsExactly("RIALTO-S003")

        // given
        contract.writeText(contract.readText().replace(url, url.replace("https://", "http://")))

        // expect
        assertThat(codes()).containsExactly("RIALTO-S005")
    }

    @Test
    fun `keeps a contract with only local sources free of a lock file`() {
        // given
        val local = shipment.resolve("shipment.rialto.yaml")

        // expect
        assertThat(ContractValidator.validate(local)).isInstanceOf(Outcome.Ok::class.java)
        assertThat(RialtoLock.of(local).file.toFile()).doesNotExist()
    }

    @Test
    fun `offline, a locked source that is not cached is RIALTO-S003`() {
        // given
        codes()
        cache.resolve("sha256").toFile().deleteRecursively()

        // when
        val diagnostic =
            ContractValidator
                .validate(
                    contract,
                    RemoteMode.OFFLINE,
                ).diagnostics
                .single()

        // then
        assertThat(diagnostic.code).isEqualTo(Code.REMOTE_UNAVAILABLE)
        assertThat(diagnostic.message).isEqualTo("$url is not cached")
        assertThat(diagnostic.hint).isEqualTo("Build once without offline mode")
    }

    @Test
    fun `a fetch that fails without a message names the exception`() {
        // given
        RemoteSources.fetch = { throw java.io.IOException() }

        // when
        val diagnostic = ContractValidator.validate(contract).diagnostics.single()

        // then
        assertThat(diagnostic.code).isEqualTo(Code.REMOTE_UNAVAILABLE)
        assertThat(diagnostic.message).isEqualTo("Cannot fetch $url: java.io.IOException")
    }

    @Test
    fun `drops the entries of sources the contract no longer uses, and reads a cached source without fetching`() {
        // given
        codes()
        val hash = sha256(served!!)
        lockFile.writeText(
            OWNER + "sources:\n  \"$url\": \"$hash\"\n  \"https://old.example/a.json\": \"$hash\"\n",
        )
        fetched = 0

        // expect
        assertThat(codes()).isEmpty()
        assertThat(fetched).isZero()
        assertThat(lock()).isEqualTo(RialtoLock.text(CONTRACT, RialtoLock.Content(mapOf(url to hash))))
    }

    @Test
    fun `an update with errors locks nothing, and one of an unreadable contract is RIALTO-K001`() {
        // given
        served = null

        // when
        val unavailable = RemoteSources.update(contract)

        // then
        assertThat(unavailable).isInstanceOf(Outcome.Nok::class.java)
        assertThat(unavailable.diagnostics.map { it.id }).containsExactly("RIALTO-S003")
        assertThat(lockFile.toFile()).doesNotExist()

        // given
        contract.writeText("- a list\n")

        // when
        val unreadable = RemoteSources.update(contract)

        // then
        assertThat(unreadable).isInstanceOf(Outcome.Nok::class.java)
        assertThat(unreadable.diagnostics.single().code).isEqualTo(Code.UNREADABLE)
    }

    private val hint = "Repair or remove shipment.rialto.lock, then run `rialto sources update`"

    @ParameterizedTest(name = "{0}")
    @MethodSource("unreadableLocks")
    fun `a lock that cannot be read is RIALTO-K010 on the contract, never taken as empty, with the other findings still reported`(
        text: String,
        reason: String,
    ) {
        // given
        val lock = lockFile
        lock.writeText(text)
        contract.writeText(contract.readText().replace("type: ShipmentId", "type: Missing"))
        fetched = 0

        // when
        val diagnostics = ContractValidator.validate(contract).diagnostics
        val error = diagnostics.single { it.code == Code.LOCK_UNUSABLE }

        // then
        assertThat(diagnostics.map { it.id }.distinct()).containsExactlyInAnyOrder("RIALTO-K010", "RIALTO-K004")
        assertThat(error.message)
            .startsWith("Cannot read the lock $lock: $reason")
            .endsWith(". Not pinned: $url")
        assertThat(error.location).isEqualTo(Location(contract))
        assertThat(error.hint).isEqualTo(hint)
        assertThat(error.requirement).isEqualTo("K4.5")
        assertThat(fetched).isZero()
        assertThat(lock.readText()).isEqualTo(text)
    }

    @ParameterizedTest(name = "[{0}]")
    @ValueSource(strings = ["", "# nothing yet\n", "other: 1\n", "sources:\n"])
    fun `an empty lock, one with only a comment and one without sources are valid`(text: String) {
        // given
        lockFile.writeText(text)

        // expect
        assertThat(codes()).isEmpty()
    }

    @Test
    fun `a directory named rialto lock is RIALTO-K010, also for an update`() {
        // given
        val lock = lockFile
        java.nio.file.Files
            .createDirectory(lock)

        // when
        val diagnostic = ContractValidator.validate(contract).diagnostics.single()

        // then
        assertThat(diagnostic.code).isEqualTo(Code.LOCK_UNUSABLE)
        assertThat(diagnostic.message).isEqualTo("Cannot read the lock $lock: it is a directory. Not pinned: $url")
        assertThat(fetched).isZero()

        // when
        val update = RemoteSources.update(contract)

        // then
        assertThat(update).isInstanceOf(Outcome.Nok::class.java)
        assertThat(update.diagnostics.single().message).isEqualTo(diagnostic.message)
        assertThat(lock.toFile()).isDirectory()
    }

    @Test
    fun `an unreadable lock next to a contract without remote sources names no sources`() {
        // given
        val local = work.resolve("local.rialto.yaml")
        local.writeText("rialto: '1.0.0'\ninfo: {name: x, version: 1.0.0}\n")
        val lock = RialtoLock.of(local).file
        lock.writeText("- a\n")

        // when
        val diagnostic = ContractValidator.validate(local).diagnostics.single()

        // then
        assertThat(diagnostic.message).isEqualTo("Cannot read the lock $lock: not a mapping")
        assertThat(diagnostic.location).isEqualTo(Location(local))
    }

    @Test
    fun `a lock that cannot be read for lack of permission gives the reason of the system`() {
        // given
        val lock = lockFile
        lock.writeText("sources: {}\n")
        org.junit.jupiter.api.Assumptions
            .assumeTrue(lock.toFile().setReadable(false))
        org.junit.jupiter.api.Assumptions
            .assumeFalse(
                java.nio.file.Files
                    .isReadable(lock),
            )
        try {
            // when
            val diagnostic = ContractValidator.validate(contract).diagnostics.single()

            // then
            assertThat(diagnostic.message)
                .isEqualTo("Cannot read the lock $lock: Permission denied. Not pinned: $url")
        } finally {
            lock.toFile().setReadable(true)
        }
    }

    @Test
    fun `a lock that must change but cannot be written is RIALTO-K010, naming the sources it could not pin`() {
        // given
        org.junit.jupiter.api.Assumptions
            .assumeTrue(work.toFile().setWritable(false))
        org.junit.jupiter.api.Assumptions
            .assumeFalse(
                java.nio.file.Files
                    .isWritable(work),
            )
        val lock = lockFile

        // when
        val diagnostic = ContractValidator.validate(contract).diagnostics.single()

        // then
        assertThat(diagnostic.code).isEqualTo(Code.LOCK_UNUSABLE)
        assertThat(diagnostic.message)
            .isEqualTo("Cannot write the lock $lock: Permission denied. Not pinned: $url")
        assertThat(diagnostic.location).isEqualTo(Location(contract))
        assertThat(diagnostic.hint).isEqualTo(hint)

        // when
        val update = RemoteSources.update(contract)

        // then
        assertThat(update).isInstanceOf(Outcome.Nok::class.java)
        assertThat(update.diagnostics.single().code).isEqualTo(Code.LOCK_UNUSABLE)
    }

    @Test
    fun `a source another run pinned while this run ran stays in the lock`() {
        // given
        val other = "https://example.com/other.json"
        RemoteSources.fetch = { uri ->
            anotherRunPins(other to otherVersion)
            if (uri.toString() == url) served!! else error("404")
        }

        // expect
        assertThat(codes()).isEmpty()
        assertThat(RialtoLock.of(contract).read().sources)
            .containsExactlyInAnyOrderEntriesOf(mapOf(url to sha256(served!!), other to otherVersion))
    }

    @Test
    fun `a source another run pinned to another hash while this run ran is a conflict, not overwritten`() {
        // given
        RemoteSources.fetch = { uri ->
            anotherRunPins(url to otherVersion)
            if (uri.toString() == url) served!! else error("404")
        }

        // when
        val diagnostics = ContractValidator.validate(contract, RemoteMode.LOCKED).diagnostics

        // then
        val error = diagnostics.single { it.code == Code.LOCK_UNUSABLE }
        assertThat(error.message).contains("Another run changed the lock").endsWith("Not pinned: $url")
        assertThat(error.hint).isEqualTo("Run again to pin the sources against the lock as it is now")
        assertThat(
            RialtoLock.of(contract).read().sources,
        ).isEqualTo(mapOf(url to otherVersion))
    }

    @Test
    fun `a lock another run holds too long leaves the new source unpinned, with a hint to wait`() {
        // given
        val real = work.toRealPath().resolve("shipment.rialto.lock")
        val lockFile =
            cache.resolve("locks").createDirectories().resolve(sha256(real.toString().toByteArray()) + ".lck")
        val original = FileLocks.timeout
        FileLocks.timeout = Duration.ofMillis(100)

        // when
        val diagnostics =
            try {
                FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { channel ->
                    channel.lock().use {
                        CompletableFuture
                            .supplyAsync { ContractValidator.validate(contract, RemoteMode.LOCKED).diagnostics }
                            .join()
                    }
                }
            } finally {
                FileLocks.timeout = original
            }

        // then
        val error = diagnostics.single { it.code == Code.LOCK_UNUSABLE }
        assertThat(error.message).contains("another run held it for more than 0 s").endsWith("Not pinned: $url")
        assertThat(error.hint).isEqualTo("Wait for the other run to finish, or stop it, then run again")
    }

    private val otherVersion = sha256("another version".toByteArray())

    private fun anotherRunPins(source: Pair<String, String>) {
        RialtoLock.of(contract).update { it.copy(sources = it.sources + source) }
    }

    @Test
    fun `contracts in one directory each keep their own lock, so a build of one never unpins the other (K4_6)`() {
        // given
        val other = "https://pay.example.com/openapi.json"
        // other bytes than the API of shipment, so the two never share a cached copy
        val payments = shipment.resolve("openapi.json").readBytes() + "\n".toByteArray()
        RemoteSources.fetch = { uri ->
            fetched++
            when (uri.toString()) {
                url -> served ?: error("offline")
                other -> payments
                else -> error("404")
            }
        }

        val billing = work.resolve("billing.rialto.yaml")
        billing.writeText(contract.readText().replace(url, other))
        assertThat(ContractValidator.validate(billing).diagnostics).isEmpty()
        val billingLock = RialtoLock.of(billing).file.readText()

        // when
        assertThat(codes()).isEmpty()

        // then
        assertThat(RialtoLock.of(billing).file.readText()).isEqualTo(billingLock)
        assertThat(RialtoLock.of(billing).read().sources).containsOnlyKeys(other)
        assertThat(RialtoLock.of(contract).read().sources).containsOnlyKeys(url)
        assertThat(billingLock).startsWith(RialtoLock.text("billing.rialto.yaml", RialtoLock.Content()).lines()[0])
        assertThat(billingLock).contains("contract: \"billing.rialto.yaml\"")

        // given: the API of billing changes upstream
        val changed = payments + " ".toByteArray()
        cache.resolve("sha256").toFile().deleteRecursively()
        RemoteSources.fetch = { uri -> if (uri.toString() == other) changed else served!! }

        // expect: a build of the other contract leaves it alone, and billing still sees the change
        assertThat(codes()).isEmpty()
        assertThat(ContractValidator.validate(billing).diagnostics.map { it.id }).containsExactly("RIALTO-S004")
    }

    @Test
    fun `two roots that share a lock name are told apart by the contract the lock names (K4_7)`() {
        // given
        codes()
        val twin = work.resolve("shipment.yaml")
        twin.writeText(contract.readText())
        val before = lock()
        fetched = 0

        // when
        val diagnostic = ContractValidator.validate(twin).diagnostics.single()

        // then
        assertThat(RialtoLock.of(twin).file).isEqualTo(lockFile)
        assertThat(diagnostic.code).isEqualTo(Code.LOCK_UNUSABLE)
        assertThat(diagnostic.message)
            .isEqualTo("Cannot read the lock $lockFile: it belongs to $CONTRACT. Not pinned: $url")
        assertThat(diagnostic.hint).isEqualTo("Repair or remove shipment.rialto.lock, then run `rialto sources update`")
        assertThat(fetched).isZero()
        assertThat(lock()).isEqualTo(before)
    }

    @Test
    fun `the system message of a file error leaves out the path`() {
        // given
        val readOnly = java.nio.file.FileSystemException("/x", null, "Read-only file system")

        // expect
        assertThat(osMessage(java.nio.file.NoSuchFileException("/x"))).isEqualTo("No such file or directory")
        assertThat(osMessage(readOnly)).isEqualTo("Read-only file system")
        assertThat(osMessage(java.nio.file.FileSystemException("/x"))).isEqualTo("FileSystemException")
        assertThat(osMessage(java.io.IOException("Disk full"))).isEqualTo("Disk full")
        assertThat(osMessage(java.io.IOException())).isEqualTo("IOException")
    }

    @Test
    fun `a lock that only drops unused entries and cannot be written is left as it is`() {
        // given
        codes()
        val hash = sha256(served!!)
        val text = OWNER + "sources:\n  \"$url\": \"$hash\"\n  \"https://old.example/a.json\": \"$hash\"\n"
        lockFile.writeText(text)
        org.junit.jupiter.api.Assumptions
            .assumeTrue(work.toFile().setWritable(false))
        org.junit.jupiter.api.Assumptions
            .assumeFalse(
                java.nio.file.Files
                    .isWritable(work),
            )

        // expect
        assertThat(codes()).isEmpty()
        assertThat(lock()).isEqualTo(text)
    }

    @Test
    fun `the cache lies in the system property, else the environment, else the home directory`() {
        // expect
        assertThat(RialtoCache.dir()).isEqualTo(cache)

        // given
        System.clearProperty("rialto.cache")
        val expected =
            System.getenv("RIALTO_CACHE")
                ?: Path.of(System.getProperty("user.home"), ".cache", "rialto").toString()

        // expect
        assertThat(RialtoCache.dir()).isEqualTo(Path.of(expected))
    }

    @Test
    fun `a locked source that left the cache is fetched again and read when it still has its hash`() {
        // given
        codes()
        val before = lock()
        cache.resolve("sha256").toFile().deleteRecursively()

        // expect
        assertThat(codes()).isEmpty()
        assertThat(fetched).isEqualTo(2)
        assertThat(lock()).isEqualTo(before)
    }

    @Test
    fun `offline, a lock with an entry the contract no longer uses is left as it is`() {
        // given
        codes()
        val text =
            lock() + "  \"https://old.example/a.json\": \"${sha256(served!!)}\"\n"
        lockFile.writeText(text)

        // expect
        assertThat(codes(RemoteMode.OFFLINE)).isEmpty()
        assertThat(lock()).isEqualTo(text)
    }

    @Test
    fun `a change is reported as url, before and after, and an update by its count`() {
        // expect
        assertThat(SourceChange("https://a", null, "2").toString()).isEqualTo("https://a: new -> 2")
        assertThat(SourceChange("https://a", "1", "2").toString()).isEqualTo("https://a: 1 -> 2")
        assertThat(SourceChange.summary(listOf(SourceChange("https://a", "1", "2")), contract))
            .isEqualTo("1 remote source(s) updated in shipment.rialto.lock")
    }

    private companion object {
        const val URL = "https://api.example.com/shipments/openapi.json"

        /** The root of the contract of these tests, and the line by which its lock names it (K4.7). */
        const val CONTRACT = "shipment.rialto.yaml"
        const val OWNER = "contract: \"$CONTRACT\"\n"

        @JvmStatic
        fun unreadableLocks() =
            listOf(
                Arguments.of("- not a mapping\n", "not a mapping"),
                Arguments.of("sources: [a]\n", "sources is not a mapping"),
                Arguments.of("sources:\n  \"$URL\": [a]\n", "the hash of $URL is not a SHA-256 in lower-case hex"),
                Arguments.of("sources: [\n", "not valid YAML: "),
                Arguments.of("sources:\n  \"$URL\": \"${"0".repeat(64)}\"\n", "it names no contract"),
                Arguments.of(
                    "contract: other.rialto.yaml\nsources:\n  \"$URL\": \"${"0".repeat(64)}\"\n",
                    "it belongs to other.rialto.yaml",
                ),
            )
    }
}
