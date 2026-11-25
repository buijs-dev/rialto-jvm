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

import dev.buijs.rialto.core.document.ContractMapper
import dev.buijs.rialto.core.utilities.FileLocks
import dev.buijs.rialto.core.utilities.isSha256
import dev.buijs.rialto.core.utilities.osMessage
import dev.buijs.rialto.core.utilities.writeAtomically
import tools.jackson.core.JacksonException
import tools.jackson.databind.JsonNode
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readText

/**
 * The lock of a contract: the SHA-256 of every remote source (K4) under `sources`, in [file] next to the root, named
 * after it ([of], K4.6). The lock names its root under `contract` (K4.7), so contracts in one directory never share
 * one, and a lock copied along with another contract is not taken for its own. Each run changes it through
 * [update]: under a file lock ([FileLocks]), it reads the lock again, changes it and replaces it atomically.
 *
 * The lock is committed with the contract and is the record of which upstream versions were accepted. Builds
 * may run side by side (Gradle and the command line on the same checkout), so a change never starts from a
 * stale copy, and a reader never sees a half-written file. [RemoteSources] decides what to pin; this class only
 * reads and writes the file safely. From slice 10 it also holds the generator pins.
 */
class RialtoLock(
    val file: Path,
    /** The file name of the root this lock belongs to, written under `contract` (K4.7). */
    val contract: String,
) {
    /** A lock that exists but is no lock: the message is the reason (K4.5). */
    class Unreadable(
        val reason: String,
    ) : IOException(reason)

    /** What the lock holds: the hash of every remote source, by URL. */
    data class Content(
        val sources: Map<String, String> = emptyMap(),
    )

    /**
     * The content, empty when there is no lock. Every reason the file cannot be read, from the file system or
     * from its content, is thrown as [Unreadable], with a message an author can act on.
     */
    fun read(): Content {
        if (Files.isDirectory(file)) {
            throw Unreadable("it is a directory")
        }

        if (!file.exists()) {
            return Content()
        }

        val root =
            try {
                ContractMapper.yaml.readTree(file.readText())
            } catch (e: JacksonException) {
                throw Unreadable("not valid YAML: ${e.originalMessage}")
            } catch (e: IOException) {
                throw Unreadable(osMessage(e))
            }

        return when {
            // An empty document, or one with only comments, is the missing node; `~` is null.
            root.isAbsent() -> Content()
            root.isObject -> Content(sources(root.path(SOURCES))).also { checkOwner(root.path(CONTRACT), it) }
            else -> throw Unreadable("not a mapping")
        }
    }

    /** A lock with entries is this contract's only when it names it (K4.7); one without entries pins nothing. */
    private fun checkOwner(
        owner: JsonNode,
        content: Content,
    ) {
        when {
            owner.isAbsent() -> {
                if (content.sources.isNotEmpty()) {
                    throw Unreadable("it names no contract")
                }
            }
            !owner.isString -> {
                throw Unreadable("contract is not a file name")
            }
            owner.asString() != contract -> {
                throw Unreadable("it belongs to ${owner.asString()}")
            }
        }
    }

    /**
     * Reads the lock, applies [change] to what it holds now and writes the result when it differs; returns the
     * new content. No other writer, in this process or another, runs in between. Throws when the lock cannot be
     * read or written.
     */
    fun update(change: (Content) -> Content): Content =
        FileLocks.exclusive(file, RialtoCache.locks()) {
            val before = read()
            val after = change(before)
            // Without a lock, before is empty: an empty result is no change and writes no lock.
            if (after != before) {
                file.writeAtomically(text(contract, after).toByteArray())
            }

            after
        }

    /** The result of [merge]. */
    internal sealed interface Merge {
        /** The lock holds the change. */
        data object Merged : Merge

        /** Another run pinned [urls] to other hashes since the change began; nothing was written. */
        data class Conflict(
            val urls: Set<String>,
        ) : Merge
    }

    /**
     * Writes the change of a run, from [base] (the sources as the run read them at its start) to [next], onto the
     * lock as it is now: another run may have written it since ([rebase]). Nothing is written on a conflict. Throws
     * like [update] when the lock cannot be read or written.
     */
    internal fun merge(
        base: Map<String, String>,
        next: Map<String, String>,
    ): Merge {
        var conflicts = emptySet<String>()
        update { current ->
            val rebased = rebase(base = base, ours = next, theirs = current.sources)
            conflicts = rebased.conflicts
            if (conflicts.isEmpty()) current.copy(sources = rebased.sources) else current
        }

        return if (conflicts.isEmpty()) Merge.Merged else Merge.Conflict(conflicts)
    }

    private fun sources(node: JsonNode): Map<String, String> =
        when {
            node.isAbsent() -> {
                emptyMap()
            }
            !node.isObject -> {
                throw Unreadable("sources is not a mapping")
            }
            else -> {
                node.properties().associate { (url, hash) ->
                    if (!hash.isString || !isSha256(hash.asString())) {
                        throw Unreadable("the hash of $url is not a SHA-256 in lower-case hex")
                    }

                    url to hash.asString()
                }
            }
        }

    private fun JsonNode.isAbsent(): Boolean = isMissingNode || isNull

    companion object {
        /** The result of [rebase]: the sources to lock, and the URLs both runs pinned differently. */
        internal data class Rebased(
            val sources: Map<String, String>,
            val conflicts: Set<String>,
        )

        /**
         * Applies our change from [base] to [ours] onto [theirs], the lock another run wrote since [base] was read.
         * A source only we changed takes our hash; one only they changed keeps theirs. One we dropped but they changed
         * stays: their contract still uses it. One we both pinned, to different hashes, is a conflict.
         */
        internal fun rebase(
            base: Map<String, String>,
            ours: Map<String, String>,
            theirs: Map<String, String>,
        ): Rebased {
            val sources = theirs.toMutableMap()
            val conflicts = linkedSetOf<String>()
            for (url in base.keys + ours.keys) {
                val before = base[url]
                val after = ours[url]
                val now = theirs[url]
                when {
                    after == before || now == after -> Unit
                    now == before -> if (after == null) sources.remove(url) else sources[url] = after
                    after != null -> conflicts += url
                }
            }

            return Rebased(sources, conflicts)
        }

        private const val CONTRACT = "contract"
        private const val SOURCES = "sources"
        private const val SUFFIX = ".rialto.lock"

        /** The lock of the contract whose root is [root]: next to it, named after it (K4.6). */
        fun of(root: Path): RialtoLock {
            val name = root.fileName.toString()
            return RialtoLock(root.toAbsolutePath().resolveSibling(fileName(name)), name)
        }

        /**
         * The file name of the lock of a root named [root] (K4.6): a trailing `.yaml`, `.yml` or `.json` removed,
         * then a trailing `.rialto`, followed by `.rialto.lock`. `shipment.rialto.yaml` and `shipment.yaml` both
         * give `shipment.rialto.lock`, a root without either keeps its whole name.
         */
        internal fun fileName(root: String): String {
            val base =
                listOf(".yaml", ".yml", ".json")
                    .firstOrNull { root.endsWith(it) }
                    ?.let { root.removeSuffix(it) }
                    ?: root
            return base.removeSuffix(".rialto") + SUFFIX
        }

        /**
         * The text of the lock of [contract] with [content]: `contract`, then `sources` sorted by URL, so the same
         * content always gives the same bytes.
         */
        internal fun text(
            contract: String,
            content: Content,
        ): String =
            buildString {
                append("# Written by Rialto: the SHA-256 of every remote source of the contract (K4). ")
                appendLine("Commit this file;")
                appendLine("# `rialto sources update` accepts new versions.")
                appendLine("$CONTRACT: ${quoted(contract)}")
                if (content.sources.isEmpty()) {
                    appendLine("sources: {}")
                } else {
                    appendLine("sources:")
                }

                content.sources.toSortedMap().forEach { (url, hash) ->
                    appendLine("  ${quoted(url)}: \"$hash\"")
                }
            }

        /** [text] as a YAML string in double quotes, so any file name or URL reads back as itself. */
        private fun quoted(text: String): String = ContractMapper.json.writeValueAsString(text)
    }
}
