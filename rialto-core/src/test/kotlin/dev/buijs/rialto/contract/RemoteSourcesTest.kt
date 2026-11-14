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
    private lateinit var contract: Path

    @BeforeEach
    fun setUp() {
        System.setProperty("rialto.cache", cache.toString())
        served = shipment.resolve("openapi.json").readBytes()
        RemoteSources.fetch =
            { uri: URI -> if (uri.toString() == url) served ?: error("offline") else error("404") }

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
        RemoteSources.fetch = { _: URI -> error("no network in tests") }
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
    fun `keeps the generators section of the lock when it locks a source`() {
        val generators =
            """
            generators:
              "maven:g:a":
                ids: ["gen"]
                version: "1.0"
                jars:
                  - {name: "a-1.0.jar", sha256: "abc"}
            """.trimIndent()
        work.resolve(RemoteSources.LOCK_FILE).writeText("sources: {}\n$generators\n")
        assertThat(codes()).isEmpty()
        assertThat(lock())
            .contains("\"$url\": \"${RemoteSources.sha256(served!!)}\"")
            .contains(generators)
    }
}
