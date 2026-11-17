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

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.URI
import java.nio.file.Path
import kotlin.io.path.copyTo
import kotlin.io.path.readBytes
import kotlin.io.path.readText
import kotlin.io.path.writeText

class RemoteSourcesTest {
    @TempDir
    lateinit var work: Path

    @TempDir
    lateinit var cache: Path

    private val url = "https://api.example.com/shipments/openapi.json"
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

    private fun lock() = work.resolve(RemoteSources.LOCK_FILE).readText()

    @Test
    fun `fetches a new remote source, locks it and reads it offline from the cache (K4_1, K4_3)`() {
        assertThat(codes()).isEmpty()
        assertThat(lock()).contains("\"$url\": \"${RemoteSources.sha256(served!!)}\"")

        served = null
        assertThat(codes(RemoteMode.OFFLINE)).isEmpty()
        assertThat(codes()).isEmpty()
    }

    @Test
    fun `rejects a changed source until it is updated on purpose (K4_2)`() {
        codes()
        val before = lock()
        cache.toFile().deleteRecursively()
        served = served!! + " ".toByteArray()

        assertThat(codes()).containsExactly("RIALTO-S004")
        assertThat(lock()).isEqualTo(before)

        val diagnostics = Diagnostics()
        val changes = RemoteSources.update(contract, diagnostics)
        assertThat(diagnostics.all).isEmpty()
        assertThat(changes).singleElement().matches {
            it.url == url &&
                it.after == RemoteSources.sha256(served!!)
        }

        assertThat(codes()).isEmpty()
    }

    @Test
    fun `rejects a source that is neither cached nor fetchable, and plain http (K4_3, K4_4)`() {
        assertThat(codes(RemoteMode.OFFLINE)).containsExactly("RIALTO-S003")
        served = null
        assertThat(codes()).containsExactly("RIALTO-S003")
        contract.writeText(contract.readText().replace(url, url.replace("https://", "http://")))
        assertThat(codes()).containsExactly("RIALTO-S005")
    }

    @Test
    fun `keeps a contract with only local sources free of a lock file`() {
        val local = shipment.resolve("shipment.rialto.yaml")
        assertThat(ContractValidator.validate(local).valid).isTrue()
        assertThat(local.parent.resolve(RemoteSources.LOCK_FILE).toFile()).doesNotExist()
    }

    @Test
    fun `keeps cache file names inside their directory`() {
        assertThat(
            RemoteSources.fileName("https://x.example/a/openapi.json?v=2"),
        ).isEqualTo("openapi.json")
        assertThat(RemoteSources.fileName("https://x.example/a/..")).isEqualTo("source")
        assertThat(RemoteSources.fileName("https://x.example/")).isEqualTo("source")
        assertThat(RemoteSources.fileName("https://x.example/a%2F..%2Fb")).isEqualTo("a_2F.._2Fb")
    }

    @Test
    fun `offline, a locked source that is not cached is RIALTO-S003`() {
        codes()
        cache.resolve("sha256").toFile().deleteRecursively()
        val diagnostic =
            ContractValidator
                .validate(
                    contract,
                    RemoteMode.OFFLINE,
                ).diagnostics
                .single()
        assertThat(diagnostic.code).isEqualTo(Code.REMOTE_UNAVAILABLE)
        assertThat(diagnostic.message).isEqualTo("$url is not cached")
        assertThat(diagnostic.hint).isEqualTo("Build once without offline mode")
    }

    @Test
    fun `a fetch that fails without a message names the exception`() {
        RemoteSources.fetch = { throw java.io.IOException() }

        val diagnostic = ContractValidator.validate(contract).diagnostics.single()
        assertThat(diagnostic.code).isEqualTo(Code.REMOTE_UNAVAILABLE)
        assertThat(diagnostic.message).isEqualTo("Cannot fetch $url: java.io.IOException")
    }

    @Test
    fun `drops the entries of sources the contract no longer uses, and reads a cached source without fetching`() {
        codes()
        val hash = RemoteSources.sha256(served!!)
        work.resolve(RemoteSources.LOCK_FILE).writeText(
            "sources:\n  \"$url\": \"$hash\"\n  \"https://old.example/a.json\": \"$hash\"\n",
        )
        fetched = 0
        assertThat(codes()).isEmpty()
        assertThat(fetched).isZero()
        assertThat(lock()).isEqualTo(RialtoLock.text(RialtoLock.Content(mapOf(url to hash))))
    }

    @Test
    fun `an update with errors locks nothing, and one of an unreadable contract is RIALTO-K001`() {
        served = null
        val diagnostics = Diagnostics()
        assertThat(RemoteSources.update(contract, diagnostics)).isEmpty()
        assertThat(diagnostics.all.map { it.id }).containsExactly("RIALTO-S003")
        assertThat(work.resolve(RemoteSources.LOCK_FILE).toFile()).doesNotExist()

        val unreadable = Diagnostics()
        contract.writeText("- a list\n")
        assertThat(RemoteSources.update(contract, unreadable)).isEmpty()
        assertThat(unreadable.all.single().code).isEqualTo(Code.UNREADABLE)
    }

    private val hint = "Repair or remove rialto.lock, then run `rialto sources update`"

    @Test
    fun `a lock that cannot be read is RIALTO-K010 on the contract, never taken as empty, with the other findings still reported`() {
        val lock = work.resolve(RemoteSources.LOCK_FILE)
        listOf(
            "- not a mapping\n" to "not a mapping",
            "sources: [a]\n" to "sources is not a mapping",
            "sources:\n  \"$url\": [a]\n" to "$url is not a URL with a hash",
            "sources: [\n" to "not valid YAML: ",
        ).forEach { (text, reason) ->
            lock.writeText(text)
            contract.writeText(contract.readText().replace("type: ShipmentId", "type: Missing"))
            fetched = 0
            val diagnostics = ContractValidator.validate(contract).diagnostics
            assertThat(
                diagnostics
                    .map { it.id }
                    .distinct(),
            ).describedAs(text).containsExactlyInAnyOrder("RIALTO-K010", "RIALTO-K004")
            val error = diagnostics.single { it.code == Code.LOCK_UNUSABLE }

            assertThat(
                error.message,
            ).startsWith("Cannot read the lock $lock: $reason").endsWith(". Not pinned: $url")
            assertThat(error.location).isEqualTo(Location(contract))
            assertThat(error.hint).isEqualTo(hint)
            assertThat(error.requirement).isEqualTo("K4.5")
            assertThat(fetched).isZero()
            assertThat(lock.readText()).isEqualTo(text)
        }
    }

    @Test
    fun `an empty lock, one with only a comment and one without sources are valid`() {
        listOf("", "# nothing yet\n", "other: 1\n", "sources:\n").forEach { text ->
            work.resolve(RemoteSources.LOCK_FILE).writeText(text)
            assertThat(codes()).describedAs(text).isEmpty()
        }
    }

    @Test
    fun `a directory named rialto lock is RIALTO-K010, also for an update`() {
        val lock = work.resolve(RemoteSources.LOCK_FILE)
        java.nio.file.Files
            .createDirectory(lock)
        val diagnostic = ContractValidator.validate(contract).diagnostics.single()
        assertThat(diagnostic.code).isEqualTo(Code.LOCK_UNUSABLE)
        assertThat(
            diagnostic.message,
        ).isEqualTo("Cannot read the lock $lock: it is a directory. Not pinned: $url")
        assertThat(fetched).isZero()

        val diagnostics = Diagnostics()
        assertThat(RemoteSources.update(contract, diagnostics)).isEmpty()
        assertThat(diagnostics.all.single().message).isEqualTo(diagnostic.message)
        assertThat(lock.toFile()).isDirectory()
    }

    @Test
    fun `an unreadable lock next to a contract without remote sources names no sources`() {
        val local = work.resolve("local.rialto.yaml")
        local.writeText("rialto: '1.0.0'\ninfo: {name: x, version: 1.0.0}\n")
        val lock = work.resolve(RemoteSources.LOCK_FILE)
        lock.writeText("- a\n")
        val diagnostic = ContractValidator.validate(local).diagnostics.single()
        assertThat(diagnostic.message).isEqualTo("Cannot read the lock $lock: not a mapping")
        assertThat(diagnostic.location).isEqualTo(Location(local))
    }

    @Test
    fun `a lock that cannot be read for lack of permission gives the reason of the system`() {
        val lock = work.resolve(RemoteSources.LOCK_FILE)
        lock.writeText("sources: {}\n")
        org.junit.jupiter.api.Assumptions
            .assumeTrue(lock.toFile().setReadable(false))
        org.junit.jupiter.api.Assumptions
            .assumeFalse(
                java.nio.file.Files
                    .isReadable(lock),
            )
        try {
            val diagnostic = ContractValidator.validate(contract).diagnostics.single()
            assertThat(
                diagnostic.message,
            ).isEqualTo("Cannot read the lock $lock: Permission denied. Not pinned: $url")
        } finally {
            lock.toFile().setReadable(true)
        }
    }

    @Test
    fun `a lock that must change but cannot be written is RIALTO-K010, naming the sources it could not pin`() {
        org.junit.jupiter.api.Assumptions
            .assumeTrue(work.toFile().setWritable(false))
        org.junit.jupiter.api.Assumptions
            .assumeFalse(
                java.nio.file.Files
                    .isWritable(work),
            )
        val lock = work.resolve(RemoteSources.LOCK_FILE)
        val diagnostic = ContractValidator.validate(contract).diagnostics.single()
        assertThat(diagnostic.code).isEqualTo(Code.LOCK_UNUSABLE)
        assertThat(
            diagnostic.message,
        ).isEqualTo("Cannot write the lock $lock: Permission denied. Not pinned: $url")
        assertThat(diagnostic.location).isEqualTo(Location(contract))
        assertThat(diagnostic.hint).isEqualTo(hint)

        val diagnostics = Diagnostics()
        assertThat(RemoteSources.update(contract, diagnostics)).isEmpty()
        assertThat(diagnostics.all.single().code).isEqualTo(Code.LOCK_UNUSABLE)
    }

    @Test
    fun `the system message of a file error leaves out the path`() {
        assertThat(
            RemoteSources.osMessage(java.nio.file.NoSuchFileException("/x")),
        ).isEqualTo("No such file or directory")
        assertThat(
            RemoteSources.osMessage(
                java.nio.file.FileSystemException("/x", null, "Read-only file system"),
            ),
        ).isEqualTo("Read-only file system")
        assertThat(
            RemoteSources.osMessage(java.nio.file.FileSystemException("/x")),
        ).isEqualTo("FileSystemException")
        assertThat(RemoteSources.osMessage(java.io.IOException("Disk full"))).isEqualTo("Disk full")
        assertThat(RemoteSources.osMessage(java.io.IOException())).isEqualTo("IOException")
    }

    @Test
    fun `a lock that only drops unused entries and cannot be written is left as it is`() {
        codes()
        val hash = RemoteSources.sha256(served!!)
        val text = "sources:\n  \"$url\": \"$hash\"\n  \"https://old.example/a.json\": \"$hash\"\n"
        work.resolve(RemoteSources.LOCK_FILE).writeText(text)
        org.junit.jupiter.api.Assumptions
            .assumeTrue(work.toFile().setWritable(false))
        org.junit.jupiter.api.Assumptions
            .assumeFalse(
                java.nio.file.Files
                    .isWritable(work),
            )
        assertThat(codes()).isEmpty()
        assertThat(lock()).isEqualTo(text)
    }

    @Test
    fun `the cache lies in the system property, else the environment, else the home directory`() {
        assertThat(RemoteSources.cacheDir()).isEqualTo(cache)
        System.clearProperty("rialto.cache")
        val expected =
            System.getenv("RIALTO_CACHE")
                ?: Path.of(System.getProperty("user.home"), ".cache", "rialto").toString()
        assertThat(RemoteSources.cacheDir()).isEqualTo(Path.of(expected))
    }
}
