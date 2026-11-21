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
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.readBytes
import kotlin.io.path.writeBytes

/** The cache: where it lies, and the remote sources it keeps by content. */
class RialtoCacheTest {
    @TempDir
    lateinit var cache: Path

    private fun <T> inCache(action: () -> T): T {
        System.setProperty("rialto.cache", cache.toString())
        return try {
            action()
        } finally {
            System.clearProperty("rialto.cache")
        }
    }

    @Test
    fun `the cache lies in the system property, else the environment variable, else the home directory`() {
        assertThat(RialtoCache.dir("/p", "/e", "/h")).isEqualTo(Path.of("/p"))
        assertThat(RialtoCache.dir(null, "/e", "/h")).isEqualTo(Path.of("/e"))
        assertThat(RialtoCache.dir(null, null, "/h")).isEqualTo(Path.of("/h/.cache/rialto"))
        assertThat(inCache { RialtoCache.dir() }).isEqualTo(cache)
        assertThat(inCache { RialtoCache.locks() }).isEqualTo(cache.resolve("locks"))
    }

    @Test
    fun `a stored document is found by its hash and file name`() {
        val body = "{}".toByteArray()
        val hash = RialtoCache.sha256(body)

        inCache {
            val file = RialtoCache.store(hash, "api.json", body)

            assertThat(file).isEqualTo(cache.resolve("sha256/$hash/api.json"))
            assertThat(file.readBytes()).isEqualTo(body)
            assertThat(RialtoCache.cached(hash, "api.json")).isEqualTo(file)
        }
    }

    @Test
    fun `a document that is missing, or whose content no longer has its hash, is not found`() {
        val hash = RialtoCache.sha256("{}".toByteArray())

        inCache {
            assertThat(RialtoCache.cached(hash, "api.json")).isNull()
            RialtoCache.store(hash, "api.json", "{}".toByteArray()).writeBytes("{ }".toByteArray())
            assertThat(RialtoCache.cached(hash, "api.json")).isNull()
        }
    }

    @Test
    fun `the hash is the lower-case hex SHA-256`() {
        assertThat(RialtoCache.sha256("abc".toByteArray()))
            .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad")
    }
}
