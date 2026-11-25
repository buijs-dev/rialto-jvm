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

package dev.buijs.rialto.core.utilities

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * Fetches [url] over HTTP and returns its body; throws on a failure or a status other than 200. The default of
 * [RemoteSources.fetch][dev.buijs.rialto.core.sources.RemoteSources.fetch], which tests replace.
 */
internal fun httpFetch(url: URI): ByteArray {
    val request =
        HttpRequest
            .newBuilder(url)
            .timeout(Duration.ofSeconds(30))
            .GET()
            .build()

    // NORMAL follows redirects, but never from https to http (K4.4).
    val response =
        HttpClient
            .newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build()
            .use { it.send(request, HttpResponse.BodyHandlers.ofByteArray()) }

    check(response.statusCode() == 200) { "HTTP ${response.statusCode()}" }
    return response.body()
}
