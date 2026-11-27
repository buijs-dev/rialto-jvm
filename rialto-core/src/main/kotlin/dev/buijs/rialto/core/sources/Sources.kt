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
import dev.buijs.rialto.core.diagnostics.Diagnostic
import dev.buijs.rialto.core.diagnostics.Diagnostics
import dev.buijs.rialto.core.diagnostics.Location
import dev.buijs.rialto.core.document.LocatedDocument
import dev.buijs.rialto.core.model.Contract
import dev.buijs.rialto.core.model.Keys
import dev.buijs.rialto.core.model.Source
import dev.buijs.rialto.core.model.SourceKind
import dev.buijs.rialto.core.utilities.Glob
import dev.buijs.rialto.core.utilities.JsonPointer
import dev.buijs.rialto.core.utilities.codePointOrder
import tools.jackson.databind.JsonNode
import java.math.BigInteger
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readText

/**
 * An external document the contract refers to, loaded and ready to look into. A contract does not repeat what
 * an OpenAPI, AsyncAPI or SQL schema already says; it points into it (`api#/paths/~1orders/get`,
 * `db#orders.id`), and this is the form in which such a pointer is resolved.
 */
sealed class LoadedSource {
    abstract val name: String
    abstract val file: Path

    /** An OpenAPI or AsyncAPI document, addressed by JSON Pointer. */
    data class Document(
        override val name: String,
        override val file: Path,
        val document: LocatedDocument,
    ) : LoadedSource()

    /** A SQL schema, addressed by `table` or `table.column`. */
    data class Sql(
        override val name: String,
        override val file: Path,
        val schema: Map<String, SqlTable>,
    ) : LoadedSource()
}

/**
 * A reference into a source, `<source>#<location>`, taken apart once: the name under `sources` and the place in
 * that source. For a SQL source the location is `table` or `table.column`, compared in lower case.
 */
data class ExternalRef(
    val source: String,
    val location: String,
) {
    /** The table of a SQL location. */
    val table: String get() = location.substringBefore('.').lowercase()

    /** The column of a SQL location, or null when it names only a table. */
    val column: String? get() =
        if ('.' in location) {
            location.substringAfter('.').lowercase()
        } else {
            null
        }

    companion object {
        fun of(reference: String): ExternalRef =
            ExternalRef(reference.substringBefore('#'), reference.substringAfter('#'))
    }
}

/**
 * The sources of one contract, and resolution of `<source>#<location>` references.
 *
 * This is the bridge between the contract and the documents it builds on: [load] reads every source once, local
 * or remote, and the checks then ask it whether a reference lands, or what is there (a SQL table, an OpenAPI
 * element), without reading files themselves.
 *
 * Loading never fails as a whole. A source that cannot be read is left out and is a finding in [diagnostics]; the
 * others are still loaded, so the checks can go on and one run shows the author every mistake.
 *
 * @property loaded the sources that could be read, by their name under `sources`; one that could not be read is
 *   missing here and is a finding in [diagnostics]
 * @property diagnostics what went wrong while loading, in the order of the sources; empty when every source was
 *   loaded
 */
class Sources(
    val loaded: Map<String, LoadedSource>,
    val diagnostics: List<Diagnostic>,
) {
    /** The result of resolving one reference. */
    sealed class Resolution {
        /** The source has the location. */
        data object Resolved : Resolution()

        /** The contract declares no source by that name. */
        data object UnknownSource : Resolution()

        /** The source is declared but could not be loaded; that was reported already. */
        data object Unloaded : Resolution()

        /** The source was loaded but has no such location. */
        data class Missing(
            val source: LoadedSource,
        ) : Resolution()
    }

    /**
     * Resolves [reference], `<source>#<location>`, against the sources [declared] by the contract. A document is
     * addressed by JSON Pointer, a SQL schema by `table` or `table.column`, in any case.
     */
    fun resolve(
        reference: String,
        declared: Set<String>,
    ): Resolution {
        val ref = ExternalRef.of(reference)
        if (ref.source !in declared) {
            return Resolution.UnknownSource
        }

        val source =
            loaded[ref.source]
                ?: return Resolution.Unloaded

        val found =
            when (source) {
                is LoadedSource.Document -> {
                    source.document.at(ref.location) != null
                }
                is LoadedSource.Sql -> {
                    val columns = source.schema[ref.table]?.columns
                    columns != null && (ref.column == null || ref.column in columns)
                }
            }

        return if (found) {
            Resolution.Resolved
        } else {
            Resolution.Missing(source)
        }
    }

    /** The SQL table `<source>#<table>` refers to, or null. */
    fun table(reference: String): SqlTable? {
        val ref = ExternalRef.of(reference)
        return (loaded[ref.source] as? LoadedSource.Sql)?.schema?.get(ref.table)
    }

    /** The element a document reference points at, or null. */
    fun element(reference: String): JsonNode? {
        val ref = ExternalRef.of(reference)
        return (loaded[ref.source] as? LoadedSource.Document)?.document?.at(ref.location)
    }

    companion object {
        /**
         * Loads every source of [contract]. The ones that cannot be read are findings in [Sources.diagnostics],
         * which the caller hands on. A local path is relative to the contract; an `https` path is read through
         * [RemoteSources] in the given [remote] mode; a SQL path with `*` reads all migrations it matches as one
         * schema.
         */
        fun load(
            contract: Contract,
            contractDocument: LocatedDocument,
            remote: RemoteMode = RemoteMode.LOCKED,
        ): Sources {
            val diagnostics = Diagnostics()
            val session = RemoteSession(contractDocument.file, remote, diagnostics)
            val loader = SourceLoader(contractDocument, session, diagnostics)

            val loaded = mutableMapOf<String, LoadedSource>()
            contract.sources.forEach { (name, source) ->
                val loadedSource = loader.load(name, source)
                if (loadedSource != null) {
                    loaded[name] = loadedSource
                }
            }

            session.finish()
            return Sources(loaded, diagnostics.all)
        }
    }
}

/**
 * Loads the sources of one contract, one at a time. A source that cannot be loaded is reported as
 * `RIALTO-S001` at its `path` in the contract (a remote source by [session], with its own codes), and gives null.
 */
private class SourceLoader(
    private val contractDocument: LocatedDocument,
    private val session: RemoteSession,
    private val diagnostics: Diagnostics,
) {
    /** A local path is relative to the contract. */
    private val base = contractDocument.file.toAbsolutePath().parent

    fun load(
        name: String,
        source: Source,
    ): LoadedSource? {
        val location = contractDocument.locate("/${Keys.SOURCES}/${JsonPointer.segment(name)}/${Keys.PATH}")
        return if (source.kind == SourceKind.SQL && '*' in source.path) {
            loadMigrations(name, source, location)
        } else {
            val file = fileOf(source, location) ?: return null
            loadFile(name, source, file, location)
        }
    }

    /** A service's migrations (K1.9): the schema is the result of all of them, in version order. */
    private fun loadMigrations(
        name: String,
        source: Source,
        location: Location,
    ): LoadedSource? {
        val files = migrations(base, source.path)
        return if (files.isEmpty()) {
            unreadable("Source '$name' matches no migration: ${source.path}", location)
            null
        } else {
            // One statement never spans two files.
            val sql = files.joinToString(";\n") { it.readText() }
            LoadedSource.Sql(name, files.first().parent, SqlSchema.read(sql))
        }
    }

    /** The file of [source]: the cached copy of a remote source, or the local file; null when a remote one failed. */
    private fun fileOf(
        source: Source,
        location: Location,
    ): Path? {
        return if (RemoteSources.isRemote(source.path)) {
            session.resolve(source.path, location)
        } else {
            base.resolve(source.path).normalize()
        }
    }

    private fun loadFile(
        name: String,
        source: Source,
        file: Path,
        location: Location,
    ): LoadedSource? {
        if (!file.exists()) {
            unreadable("Source '$name' not found: ${source.path}", location)
            return null
        }

        return try {
            if (source.kind == SourceKind.SQL) {
                LoadedSource.Sql(name, file, SqlSchema.read(file.readText()))
            } else {
                LoadedSource.Document(name, file, LocatedDocument.read(file))
            }
        } catch (e: Exception) {
            unreadable("Source '$name' cannot be read: ${e.message}", location)
            null
        }
    }

    private fun unreadable(
        message: String,
        location: Location,
    ) = diagnostics.error(Code.SOURCE_UNREADABLE, message, location)
}

/**
 * The files an SQL source path with `*` or `**` matches (K1.9), in version order: the number after a
 * leading `V` up to `__`, part by part; files without a version follow in code point order. A part is a number of
 * any size, so a timestamp such as `V20261120120000` is a version too.
 *
 * This is the order a migration tool such as Flyway applies them, so the schema read is the one the database
 * ends up with. The Gradle plugin also uses it to track every migration as an input of its tasks.
 */
fun migrations(
    base: Path,
    path: String,
): List<Path> {
    val files = Glob.files(base, path)
    val version = Regex("^V(\\d+(?:[._]\\d+)*)__")

    fun versionOf(file: Path): List<BigInteger>? =
        version
            .find(file.fileName.toString())
            ?.groupValues
            ?.get(1)
            ?.split('.', '_')
            ?.map { it.toBigInteger() }

    val comparator =
        Comparator<Path> { a, b ->
            val (va, vb) = versionOf(a) to versionOf(b)
            when {
                va != null && vb != null -> {
                    va.zip(vb).firstOrNull { (x, y) -> x != y }?.let { (x, y) -> x.compareTo(y) }
                        ?: va.size.compareTo(vb.size)
                }
                va != null -> {
                    -1
                }
                vb != null -> {
                    1
                }
                else -> {
                    codePointOrder.compare(a.toString(), b.toString())
                }
            }
        }

    return files.sortedWith(comparator)
}
