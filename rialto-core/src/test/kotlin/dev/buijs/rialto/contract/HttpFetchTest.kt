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

import com.sun.net.httpserver.HttpServer
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URI

/** The network fetch of remote sources, against a server on the loopback interface. */
class HttpFetchTest {
    private lateinit var server: HttpServer

    private fun uri(path: String) = URI.create("http://127.0.0.1:${server.address.port}$path")

    @BeforeEach
    fun start() {
        server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        server.createContext("/api.json") { exchange ->
            val body = """{"openapi": "3.1.0"}""".toByteArray()
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }

        server.createContext("/moved") { exchange ->
            exchange.responseHeaders.add("Location", "/api.json")
            exchange.sendResponseHeaders(302, -1)
            exchange.close()
        }

        server.createContext("/missing") { exchange ->
            exchange.sendResponseHeaders(404, -1)
            exchange.close()
        }

        server.start()
    }

    @AfterEach
    fun stop() = server.stop(0)

    @Test
    fun `returns the body of a 200 response, also after a redirect`() {
        assertThat(
            String(RemoteSources.fetch(uri("/api.json"))),
        ).isEqualTo("""{"openapi": "3.1.0"}""")
        assertThat(String(RemoteSources.fetch(uri("/moved")))).isEqualTo("""{"openapi": "3.1.0"}""")
    }

    @Test
    fun `throws on any other status`() {
        assertThatThrownBy { RemoteSources.fetch(uri("/missing")) }.hasMessage("HTTP 404")
    }
}
