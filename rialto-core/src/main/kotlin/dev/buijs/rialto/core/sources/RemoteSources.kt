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

import dev.buijs.rialto.core.diagnostics.Diagnostics
import dev.buijs.rialto.core.diagnostics.Location
import dev.buijs.rialto.core.diagnostics.Outcome
import dev.buijs.rialto.core.document.LocatedDocument
import dev.buijs.rialto.core.model.Keys
import dev.buijs.rialto.core.utilities.JsonPointer
import dev.buijs.rialto.core.utilities.httpFetch
import java.net.URI
import java.nio.file.Path

/**
 * How remote sources are read (K4). Each mode is a policy: whether a pinned source is read from the cache, whether
 * the network is used, whether a hash other than the pin is an error, and whether the lock is written.
 */
enum class RemoteMode(
    /** A pinned source is read from the cache when it is there. */
    internal val readsCache: Boolean,
    /** A source that is not read from the cache is fetched. */
    internal val fetches: Boolean,
    /** A fetched source whose hash differs from its pin is rejected (`RIALTO-S004`). */
    internal val enforcesPins: Boolean,
    /** The lock is written at the end of a run without errors. */
    internal val writesLock: Boolean,
) {
    /** Locked sources from the cache or fetched and checked; new sources fetched and locked. */
    LOCKED(readsCache = true, fetches = true, enforcesPins = true, writesLock = true),

    /** Only the cache; a source that is not cached, or not locked, is rejected. */
    OFFLINE(readsCache = true, fetches = false, enforcesPins = true, writesLock = false),

    /** Every remote source fetched again and locked with its current hash. */
    UPDATE(readsCache = false, fetches = true, enforcesPins = false, writesLock = true),
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
        /** The last line of an update of the contract at [root] that had no errors. */
        fun summary(
            changes: List<SourceChange>,
            root: Path,
        ): String = "${changes.size} remote source(s) updated in ${RialtoLock.of(root).file.fileName}"
    }
}

/**
 * Remote sources (K4): documents at an `https` URL, pinned by SHA-256 in the lock of the contract ([RialtoLock]),
 * next to its root, and kept by content in the [RialtoCache].
 *
 * A contract may build on an API another team publishes. Pinning its hash makes every build read exactly the
 * version that was reviewed and committed: a change upstream fails the build (`RIALTO-S004`) until someone
 * accepts it with [update], and a build without network can still run from the cache.
 */
object RemoteSources {
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
        val session = RemoteSession(contractFile, RemoteMode.UPDATE, diagnostics)
        remoteSourcesOf(document).forEach { (url, location) -> session.resolve(url, location) }
        return Outcome.of(session.finish(), diagnostics.all)
    }

    /** The URL of every remote source of [document], with the location of its `path`. */
    private fun remoteSourcesOf(document: LocatedDocument): List<Pair<String, Location>> =
        document.root
            .path(Keys.SOURCES)
            .properties()
            .map { (name, source) -> name to source.path(Keys.PATH).asString() }
            .filter { (_, path) -> isRemote(path) }
            .map { (name, url) ->
                url to document.locate("/${Keys.SOURCES}/${JsonPointer.segment(name)}/${Keys.PATH}")
            }
}
