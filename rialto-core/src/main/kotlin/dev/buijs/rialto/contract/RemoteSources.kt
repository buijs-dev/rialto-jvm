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

import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Path
import java.time.Duration

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
) {
    /** As `rialto sources update` and `rialtoSourcesUpdate` report it: `<url>: <before or new> -> <after>`. */
    override fun toString(): String = "$url: ${before ?: "new"} -> $after"

    companion object {
        /** The last line of an update that had no errors. */
        fun summary(changes: List<SourceChange>): String =
            "${changes.size} remote source(s) updated in ${RemoteSources.LOCK_FILE}"
    }
}

/**
 * Remote sources (K4): documents at an `https` URL, pinned by SHA-256 in `rialto.lock` next to the
 * contract and kept by content in the [RialtoCache].
 *
 * A contract may build on an API another team publishes. Pinning its hash makes every build read exactly the
 * version that was reviewed and committed: a change upstream fails the build (`RIALTO-S004`) until someone
 * accepts it with [update], and a build without network can still run from the cache.
 */
object RemoteSources {
    /** The name of the lock file, next to the contract. */
    const val LOCK_FILE = "rialto.lock"

    /** Fetches the body of a URL; throws on a failure or a status other than 200. Replaceable in tests. */
    var fetch: (URI) -> ByteArray = ::httpFetch

    /** Whether a source path is a URL rather than a local file; a URL that is not `https` is rejected later. */
    fun isRemote(path: String): Boolean = "://" in path

    /**
     * Fetches every remote source of [contractFile] and locks its current hash; returns what changed. This is
     * `rialto sources update` and `rialtoSourcesUpdate`: the deliberate step that accepts a new upstream version.
     * It is [Outcome.Nok] when the contract cannot be read, a source cannot be fetched, or the lock cannot be
     * read or written; then the lock is left as it was.
     */
    fun update(contractFile: Path): Outcome<List<SourceChange>> {
        val document =
            when (val read = LocatedDocument.readMapping(contractFile)) {
                is Outcome.Nok -> return read
                is Outcome.Ok -> read.value
            }

        val diagnostics = Diagnostics()
        val session = Session(contractFile, RemoteMode.UPDATE, diagnostics)
        document.root.path(Keys.SOURCES).properties().forEach { (name, source) ->
            val path = source.path(Keys.PATH).asString()
            if (isRemote(path)) {
                session.resolve(
                    path,
                    document.locate("/${Keys.SOURCES}/${LocatedDocument.segment(name)}/${Keys.PATH}"),
                )
            }
        }

        val changes = session.finish()
        return Outcome.of(changes, diagnostics.all)
    }

    /**
     * One run over the remote sources of a contract: reads `rialto.lock`, writes it in [finish].
     *
     * The caller calls [resolve] for each remote source and [finish] once at the end, so the lock is written at
     * most once per run, and only when the run had no errors: a failed run never pins a half-checked state.
     *
     * A session is a part of the step that creates it ([update], or [Sources.load]) and reports into the
     * collector of that step, [diagnostics], so its findings stand between the others in the order of the
     * sources. [finish] looks at every error in that collector, not only at those of the remote sources.
     */
    internal class Session(
        private val contractFile: Path,
        private val mode: RemoteMode,
        private val diagnostics: Diagnostics,
    ) {
        private val lockFile = contractFile.toAbsolutePath().parent.resolve(LOCK_FILE)

        /** Why the lock cannot be read, or null when it can (or does not exist). */
        private var unreadable: String? = null

        /** The locked hashes, or null when the lock exists but cannot be read: never taken as an empty lock. */
        private val locked: Map<String, String>? = readLock()
        private val hashes = linkedMapOf<String, String>()

        /** The remote sources an unreadable lock leaves unpinned. */
        private val unpinned = linkedSetOf<String>()

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

            // An unreadable lock is reported once, in finish (RIALTO-K010); its sources are neither fetched nor pinned.
            val locked =
                locked ?: run {
                    unpinned += url
                    return null
                }

            val pinned = locked[url]
            val name = fileName(url)

            if (mode != RemoteMode.UPDATE && pinned != null) {
                val cached = RialtoCache.cached(pinned, name)
                if (cached != null) {
                    return cached.also { hashes[url] = pinned }
                }
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

            val hash = RialtoCache.sha256(body)
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
            return RialtoCache.store(hash, name, body)
        }

        /** Writes `rialto.lock` when a source was added or updated; returns the changed hashes. */
        fun finish(): List<SourceChange> {
            val locked =
                locked ?: run {
                    lockError("Cannot read the lock $lockFile: $unreadable", unpinned)
                    return emptyList()
                }

            if (diagnostics.hasErrors) {
                return emptyList()
            }

            val changes =
                hashes
                    .filter { (url, hash) -> locked[url] != hash }
                    .map { (url, hash) -> SourceChange(url, locked[url], hash) }

            // The new lock holds exactly the sources of this run: an entry of a source the contract no longer uses
            // is dropped.
            val next: Map<String, String> = hashes

            if (next != locked && mode != RemoteMode.OFFLINE) {
                try {
                    writeLock(next)
                } catch (e: IOException) {
                    // Dropping an entry that is no longer used can wait for the next run; a new or changed hash cannot.
                    if (changes.isEmpty()) {
                        return changes
                    }

                    lockError(
                        "Cannot write the lock $lockFile: ${RialtoLock.osMessage(e)}",
                        changes.map { it.url },
                    )
                    return emptyList()
                }
            }

            return changes
        }

        /** RIALTO-K010 on the contract (K4.5), naming the lock and the sources it leaves unpinned. */
        private fun lockError(
            message: String,
            unpinned: Collection<String>,
        ) {
            val sources =
                if (unpinned.isEmpty()) {
                    ""
                } else {
                    ". Not pinned: ${unpinned.joinToString(
                        ", ",
                    )}"
                }

            diagnostics.error(
                Code.LOCK_UNUSABLE,
                message + sources,
                Location(contractFile),
                "Repair or remove $LOCK_FILE, then run `rialto sources update`",
            )
        }

        private fun readLock(): Map<String, String>? =
            try {
                RialtoLock(lockFile).read().sources
            } catch (e: RialtoLock.Unreadable) {
                unreadable = e.message
                null
            }

        /** Writes the sources section. */
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
