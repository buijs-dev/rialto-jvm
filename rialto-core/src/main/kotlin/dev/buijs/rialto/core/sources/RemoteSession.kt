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
import dev.buijs.rialto.core.diagnostics.Diagnostics
import dev.buijs.rialto.core.diagnostics.Location
import dev.buijs.rialto.core.utilities.FileLocks
import dev.buijs.rialto.core.utilities.osMessage
import dev.buijs.rialto.core.utilities.sha256
import java.io.IOException
import java.net.URI
import java.nio.file.Path

/**
 * One run over the remote sources of a contract: reads its lock, writes it in [finish].
 *
 * The caller calls [resolve] for each remote source and [finish] once at the end, so the lock is written at
 * most once per run, and only when the run had no errors: a failed run never pins a half-checked state.
 *
 * A session is a part of the step that creates it ([RemoteSources.update], or [Sources.load]) and reports into the
 * collector of that step, [diagnostics], so its findings stand between the others in the order of the
 * sources. [finish] looks at every error in that collector, not only at those of the remote sources.
 */
internal class RemoteSession(
    private val contractFile: Path,
    private val mode: RemoteMode,
    private val diagnostics: Diagnostics,
) {
    private val rialtoLock = RialtoLock.of(contractFile)
    private val lockFile = rialtoLock.file
    private val lockName = lockFile.fileName.toString()

    /** The lock as this run read it at the start. */
    private val lock: LockState = readLock()

    /** The hash this run pins for every remote source it resolved: the next content of the lock. */
    private val accepted = linkedMapOf<String, String>()

    /** The remote sources an unreadable lock leaves unpinned. */
    private val unpinned = linkedSetOf<String>()

    /** The cached copy of [url], fetched when needed, or null (reported at [location]). */
    fun resolve(
        url: String,
        location: Location,
    ): Path? {
        if (!url.startsWith("https://")) {
            return rejectScheme(url, location)
        }

        val pins =
            when (lock) {
                // Reported once, in finish (RIALTO-K010); its sources are neither fetched nor pinned.
                is LockState.Unreadable -> {
                    unpinned += url
                    return null
                }
                is LockState.Readable -> {
                    lock.pins
                }
            }

        val source = Remote(url, pins[url], RialtoCache.nameOf(url), location)
        return fromCache(source) ?: download(source)
    }

    /** Writes the lock when a source was added or updated; returns the changed hashes. */
    fun finish(): List<SourceChange> {
        val pins =
            when (lock) {
                is LockState.Unreadable -> return reportUnreadableLock(lock.reason)
                is LockState.Readable -> lock.pins
            }

        // A failed run never pins a state that was only partly checked.
        if (diagnostics.hasErrors) {
            return emptyList()
        }

        val changes = changesSince(pins)

        // The new lock holds exactly the sources of this run: an entry the contract no longer uses is dropped.
        if (!mode.writesLock || accepted == pins) {
            return changes
        }

        return when (val written = writeLock(base = pins, next = accepted)) {
            is WriteResult.Written -> changes
            is WriteResult.Conflict -> reportConflict(written.urls)
            // Dropping an entry that is no longer used can wait for the next run; a new or changed hash cannot.
            is WriteResult.Failed -> if (changes.isEmpty()) changes else reportWriteFailure(written.error, changes)
        }
    }

    // ---- resolve: the steps ----------------------------------------------------------------------------------

    /** The cached copy of a pinned [source], when this mode reads the cache and the copy is there. */
    private fun fromCache(source: Remote): Path? {
        val pin = source.pin?.takeIf { mode.readsCache } ?: return null
        return RialtoCache.cached(pin, source.name)?.also { accepted[source.url] = pin }
    }

    /** Fetches [source], checks it against its pin and stores it in the cache. */
    private fun download(source: Remote): Path? {
        if (!mode.fetches) {
            return rejectOffline(source)
        }

        val body = fetchBody(source) ?: return null
        val hash = sha256(body)
        if (mode.enforcesPins && source.pin != null && hash != source.pin) {
            return rejectChanged(source)
        }

        accepted[source.url] = hash
        return RialtoCache.store(hash, source.name, body)
    }

    /** The body of [source], or null when it cannot be fetched (RIALTO-S003). */
    private fun fetchBody(source: Remote): ByteArray? =
        try {
            RemoteSources.fetch(URI.create(source.url))
        } catch (e: Exception) {
            diagnostics.error(
                code = Code.REMOTE_UNAVAILABLE,
                message = "Cannot fetch ${source.url}: ${e.message ?: e}",
                location = source.location,
            )
            null
        }

    private fun rejectScheme(
        url: String,
        location: Location,
    ): Path? {
        diagnostics.error(
            code = Code.REMOTE_SCHEME,
            message = "Remote source $url does not use https",
            location = location,
        )
        return null
    }

    private fun rejectOffline(source: Remote): Path? {
        diagnostics.error(
            code = Code.REMOTE_UNAVAILABLE,
            message =
                if (source.pin == null) {
                    "${source.url} is not in $lockName and cannot be fetched offline"
                } else {
                    "${source.url} is not cached"
                },
            location = source.location,
            hint = "Build once without offline mode",
        )
        return null
    }

    private fun rejectChanged(source: Remote): Path? {
        diagnostics.error(
            code = Code.REMOTE_CHANGED,
            message = "${source.url} changed since it was locked in $lockName",
            location = source.location,
            hint = "Run `rialto sources update` (or rialtoSourcesUpdate) to accept the new version",
        )
        return null
    }

    // ---- finish: the steps -----------------------------------------------------------------------------------

    /** The sources this run pins to another hash than [pins], the lock as this run read it. */
    private fun changesSince(pins: Map<String, String>): List<SourceChange> =
        accepted
            .filter { (url, hash) -> pins[url] != hash }
            .map { (url, hash) -> SourceChange(url, pins[url], hash) }

    /**
     * Writes the changes of this run, from [base] (the lock as this run read it) to [next], onto the lock as it
     * is now: another run may have written it since. Nothing is written on a conflict.
     */
    private fun writeLock(
        base: Map<String, String>,
        next: Map<String, String>,
    ): WriteResult =
        try {
            when (val merged = rialtoLock.merge(base = base, next = next)) {
                RialtoLock.Merge.Merged -> WriteResult.Written
                is RialtoLock.Merge.Conflict -> WriteResult.Conflict(merged.urls)
            }
        } catch (e: IOException) {
            WriteResult.Failed(e)
        }

    private fun reportUnreadableLock(reason: String): List<SourceChange> {
        lockError("Cannot read the lock $lockFile: $reason", unpinned)
        return emptyList()
    }

    private fun reportConflict(urls: Set<String>): List<SourceChange> {
        lockError(
            "Another run changed the lock $lockFile while this run pinned the same sources",
            urls,
            hint = "Run again to pin the sources against the lock as it is now",
        )
        return emptyList()
    }

    private fun reportWriteFailure(
        error: IOException,
        changes: List<SourceChange>,
    ): List<SourceChange> {
        val hint =
            if (error is FileLocks.Timeout) {
                "Wait for the other run to finish, or stop it, then run again"
            } else {
                repairLock(lockName)
            }

        lockError("Cannot write the lock $lockFile: ${osMessage(error)}", changes.map { it.url }, hint)
        return emptyList()
    }

    /** RIALTO-K010 on the contract (K4.5), naming the lock and the sources it leaves unpinned. */
    private fun lockError(
        message: String,
        unpinned: Collection<String>,
        hint: String = repairLock(lockName),
    ) {
        val sources = if (unpinned.isEmpty()) "" else ". Not pinned: ${unpinned.joinToString(", ")}"
        diagnostics.error(Code.LOCK_UNUSABLE, message + sources, Location(contractFile), hint)
    }

    private fun readLock(): LockState =
        try {
            LockState.Readable(rialtoLock.read().sources)
        } catch (e: RialtoLock.Unreadable) {
            LockState.Unreadable(e.reason)
        }

    /** The lock as a run reads it: never an unreadable lock taken as an empty one. */
    private sealed interface LockState {
        data class Readable(
            val pins: Map<String, String>,
        ) : LockState

        data class Unreadable(
            val reason: String,
        ) : LockState
    }

    /** One remote source in a run: its [url], its [pin] in the lock (if any), and its [name] in the cache. */
    private data class Remote(
        val url: String,
        val pin: String?,
        val name: String,
        val location: Location,
    )

    /** The result of [writeLock]. */
    private sealed interface WriteResult {
        data object Written : WriteResult

        data class Conflict(
            val urls: Set<String>,
        ) : WriteResult

        data class Failed(
            val error: IOException,
        ) : WriteResult
    }
}

/** The hint of an unusable lock named [lockName]. */
private fun repairLock(lockName: String) = "Repair or remove $lockName, then run `rialto sources update`"
