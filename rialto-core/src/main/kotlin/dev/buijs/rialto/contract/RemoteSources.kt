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

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.time.Duration
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readBytes

/** How remote sources are read (K4). */
enum class RemoteMode {
    /** Locked sources from the cache or fetched and checked; new sources fetched and locked. */
    LOCKED,

    /** Only the cache; a source that is not cached, or not locked, is rejected. */
    OFFLINE,

    /** Every remote source fetched again and locked with its current hash. */
    UPDATE,
}

/** A remote source whose locked hash an update changed. */
data class SourceChange(
    val url: String,
    val before: String?,
    val after: String,
)

/**
 * Remote sources (K4): documents at an `https` URL, pinned by SHA-256 in `rialto.lock` next to the
 * contract and kept in a content-addressed cache, `~/.cache/rialto/sha256/<hash>/<file name>`
 * (system property `rialto.cache` or environment variable `RIALTO_CACHE` move it).
 */
object RemoteSources {
    const val LOCK_FILE = "rialto.lock"

    /** Fetches the body of a URL; throws on a failure or a status other than 200. Replaceable in tests. */
    var fetch: (URI) -> ByteArray = ::httpFetch

    fun isRemote(path: String): Boolean = "://" in path

    fun cacheDir(): Path =
        Path.of(
            System.getProperty("rialto.cache")
                ?: System.getenv("RIALTO_CACHE")
                ?: Path.of(System.getProperty("user.home"), ".cache", "rialto").toString(),
        )

    /** Fetches every remote source of [contractFile] and locks its current hash; returns what changed. */
    fun update(
        contractFile: Path,
        diagnostics: Diagnostics,
    ): List<SourceChange> {
        val document =
            try {
                LocatedDocument.readMapping(contractFile)
            } catch (e: Exception) {
                diagnostics.error(
                    Code.UNREADABLE,
                    e.message ?: "cannot be read",
                    Location(contractFile),
                )
                return emptyList()
            }

        val session = Session(contractFile, RemoteMode.UPDATE, diagnostics)
        document.root.path("sources").fields().forEach { (name, source) ->
            val path = source.path("path").asText()
            if (isRemote(path)) {
                session.resolve(
                    path,
                    document.locate("/sources/${LocatedDocument.segment(name)}/path"),
                )
            }
        }

        return session.finish()
    }

    /** One run over the remote sources of a contract: reads `rialto.lock`, writes it in [finish]. */
    class Session(
        contractFile: Path,
        private val mode: RemoteMode,
        private val diagnostics: Diagnostics,
    ) {
        private val lockFile = contractFile.toAbsolutePath().parent.resolve(LOCK_FILE)
        private val locked: Map<String, String> = readLock()
        private val hashes = linkedMapOf<String, String>()

        /** The cached copy of [url], fetched when needed, or null (reported at [location]). */
        fun resolve(
            url: String,
            location: Location,
        ): Path? {
            if (!url.startsWith("https://")) {
                diagnostics.error(
                    Code.REMOTE_SCHEME,
                    "Remote source $url does not use https",
                    location,
                )
                return null
            }

            val pinned = locked[url]
            val name = fileName(url)

            if (mode != RemoteMode.UPDATE && pinned != null) {
                val cached = cached(pinned, name)
                if (cached != null) return cached.also { hashes[url] = pinned }
            }

            if (mode == RemoteMode.OFFLINE) {
                diagnostics.error(
                    Code.REMOTE_UNAVAILABLE,
                    if (pinned == null) {
                        "$url is not in $LOCK_FILE and cannot be fetched offline"
                    } else {
                        "$url is not cached"
                    },
                    location,
                    "Build once without offline mode",
                )
                return null
            }

            val body =
                try {
                    fetch(URI.create(url))
                } catch (e: Exception) {
                    diagnostics.error(
                        Code.REMOTE_UNAVAILABLE,
                        "Cannot fetch $url: ${e.message ?: e}",
                        location,
                    )
                    return null
                }

            val hash = sha256(body)
            if (mode != RemoteMode.UPDATE && pinned != null && hash != pinned) {
                diagnostics.error(
                    Code.REMOTE_CHANGED,
                    "$url changed since it was locked in $LOCK_FILE",
                    location,
                    "Run `rialto sources update` (or rialtoSourcesUpdate) to accept the new version",
                )
                return null
            }

            hashes[url] = hash
            return store(hash, name, body)
        }

        /** Writes `rialto.lock` when a source was added or updated; returns the changed hashes. */
        fun finish(): List<SourceChange> {
            if (diagnostics.hasErrors) return emptyList()
            val changes =
                hashes
                    .filter { (url, hash) -> locked[url] != hash }
                    .map { (url, hash) -> SourceChange(url, locked[url], hash) }

            // Entries of sources the contract no longer uses are dropped with any other change.
            val next =
                if (mode == RemoteMode.UPDATE) {
                    hashes
                } else {
                    locked.filterKeys { it in hashes } + hashes
                }

            if (next != locked && mode != RemoteMode.OFFLINE) writeLock(next)
            return changes
        }

        private fun readLock(): Map<String, String> =
            try {
                RialtoLock(lockFile).read().sources
            } catch (e: Exception) {
                diagnostics.error(
                    Code.UNREADABLE,
                    "$LOCK_FILE cannot be read: ${e.message}",
                    Location(lockFile),
                )
                emptyMap()
            }

        /** Writes the sources section; the generators section stays as it is, also when written meanwhile. */
        private fun writeLock(hashes: Map<String, String>) {
            RialtoLock(lockFile).update { it.copy(sources = hashes) }
        }
    }

    /** The file name the cache keeps a document under: the last path segment, made safe for any file system. */
    internal fun fileName(url: String): String {
        val segment =
            url
                .substringBefore('#')
                .substringBefore('?')
                .substringAfterLast('/')
                .replace(Regex("[^A-Za-z0-9._-]"), "_")
        return segment.takeUnless { it.isEmpty() || it.all { char -> char == '.' } } ?: "source"
    }

    private fun cached(
        hash: String,
        name: String,
    ): Path? {
        val file = cacheDir().resolve("sha256").resolve(hash).resolve(name)
        return file.takeIf { it.exists() && sha256(it.readBytes()) == hash }
    }

    private fun store(
        hash: String,
        name: String,
        body: ByteArray,
    ): Path {
        val directory = cacheDir().resolve("sha256").resolve(hash).createDirectories()
        val file = directory.resolve(name)
        val temporary = Files.createTempFile(directory, name, ".part")
        Files.write(temporary, body)
        Files.move(
            temporary,
            file,
            StandardCopyOption.REPLACE_EXISTING,
            StandardCopyOption.ATOMIC_MOVE,
        )
        return file
    }

    fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun httpFetch(url: URI): ByteArray {
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
}
