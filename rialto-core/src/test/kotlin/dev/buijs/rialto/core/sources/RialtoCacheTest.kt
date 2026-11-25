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

import dev.buijs.rialto.core.utilities.sha256
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
        // expect
        assertThat(RialtoCache.dir(property = "/p", environment = "/e", home = "/h")).isEqualTo(Path.of("/p"))
        assertThat(RialtoCache.dir(property = null, environment = "/e", home = "/h")).isEqualTo(Path.of("/e"))
        assertThat(
            RialtoCache.dir(property = null, environment = null, home = "/h"),
        ).isEqualTo(Path.of("/h/.cache/rialto"))
        assertThat(inCache { RialtoCache.dir() }).isEqualTo(cache)
        assertThat(inCache { RialtoCache.locks() }).isEqualTo(cache.resolve("locks"))
    }

    @Test
    fun `a stored document is found by its hash and file name`() {
        // given
        val body = "{}".toByteArray()
        val hash = sha256(body)

        inCache {
            // when
            val file = RialtoCache.store(hash, "api.json", body)

            // then
            assertThat(file).isEqualTo(cache.resolve("sha256/$hash/api.json"))
            assertThat(file.readBytes()).isEqualTo(body)
            assertThat(RialtoCache.cached(hash, "api.json")).isEqualTo(file)
        }
    }

    @Test
    fun `a document that is missing, or whose content no longer has its hash, is not found`() {
        // given
        val hash = sha256("{}".toByteArray())

        inCache {
            // expect
            assertThat(RialtoCache.cached(hash, "api.json")).isNull()

            // when
            RialtoCache.store(hash, "api.json", "{}".toByteArray()).writeBytes("{ }".toByteArray())

            // then
            assertThat(RialtoCache.cached(hash, "api.json")).isNull()
        }
    }

    @Test
    fun `keeps cache file names inside their directory`() {
        // expect
        assertThat(RialtoCache.nameOf("https://x.example/a/openapi.json?v=2")).isEqualTo("openapi.json")
        assertThat(RialtoCache.nameOf("https://x.example/a/..")).isEqualTo("source")
        assertThat(RialtoCache.nameOf("https://x.example/")).isEqualTo("source")
        assertThat(RialtoCache.nameOf("https://x.example/a%2F..%2Fb")).isEqualTo("a_2F.._2Fb")
    }
}
