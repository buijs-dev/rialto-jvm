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

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readText

/** An external document the contract refers to. */
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
    ) : LoadedSource() {
        /** The column names per table. */
        val tables: Map<String, Set<String>> get() = schema.mapValues { it.value.columns.keys }
    }
}

/** The sources of one contract, and resolution of `<source>#<location>` references. */
class Sources(
    val loaded: Map<String, LoadedSource>,
) {
    /** The result of resolving one reference. */
    sealed class Resolution {
        data object Resolved : Resolution()

        data object UnknownSource : Resolution()

        data object Unloaded : Resolution()

        data class Missing(
            val source: LoadedSource,
        ) : Resolution()
    }

    fun resolve(
        reference: String,
        declared: Set<String>,
    ): Resolution {
        val name = reference.substringBefore('#')
        val location = reference.substringAfter('#')
        if (name !in declared) return Resolution.UnknownSource
        val source = loaded[name] ?: return Resolution.Unloaded
        val found =
            when (source) {
                is LoadedSource.Document -> {
                    source.document.at(location) != null
                }
                is LoadedSource.Sql -> {
                    val table = source.tables[location.substringBefore('.').lowercase()]
                    table != null &&
                        (
                            !location.contains('.') ||
                                location.substringAfter('.').lowercase() in table
                        )
                }
            }

        return if (found) Resolution.Resolved else Resolution.Missing(source)
    }

    /** The SQL table `<source>#<table>` refers to, or null. */
    fun table(reference: String): SqlTable? =
        (loaded[reference.substringBefore('#')] as? LoadedSource.Sql)
            ?.schema
            ?.get(reference.substringAfter('#').lowercase())

    /** The element a document reference points at, or null. */
    fun element(reference: String) =
        (
            loaded[
                reference.substringBefore(
                    '#',
                ),
            ] as? LoadedSource.Document
        )?.document?.at(reference.substringAfter('#'))

    companion object {
        /** Loads every source of [contract], reporting the ones that cannot be read. */
        fun load(
            contract: Contract,
            contractDocument: LocatedDocument,
            diagnostics: Diagnostics,
            remote: RemoteMode = RemoteMode.LOCKED,
        ): Sources {
            val base = contractDocument.file.toAbsolutePath().parent
            val loaded = mutableMapOf<String, LoadedSource>()
            val session = RemoteSources.Session(contractDocument.file, remote, diagnostics)
            contract.sources.forEach { (name, source) ->
                val pointer = "/sources/${LocatedDocument.segment(name)}/path"
                if (source.kind == SourceKind.SQL && '*' in source.path) {
                    // A service's migrations (K1.9): the schema is the result of all of them, in version order.
                    val files = migrations(base, source.path)
                    if (files.isEmpty()) {
                        diagnostics.error(
                            Code.SOURCE_UNREADABLE,
                            "Source '$name' matches no migration: ${source.path}",
                            contractDocument.locate(pointer),
                        )
                    } else {
                        loaded[name] =
                            LoadedSource.Sql(
                                name,
                                files.first().parent,
                                // One statement never spans two files.
                                SqlSchema.read(files.joinToString(";\n") { it.readText() }),
                            )
                    }

                    return@forEach
                }

                val file =
                    if (RemoteSources.isRemote(source.path)) {
                        session.resolve(source.path, contractDocument.locate(pointer))
                            ?: return@forEach
                    } else {
                        base.resolve(source.path).normalize()
                    }

                if (!file.exists()) {
                    diagnostics.error(
                        Code.SOURCE_UNREADABLE,
                        "Source '$name' not found: ${source.path}",
                        contractDocument.locate(pointer),
                    )
                    return@forEach
                }

                try {
                    loaded[name] =
                        when (source.kind) {
                            SourceKind.SQL -> {
                                LoadedSource.Sql(
                                    name,
                                    file,
                                    SqlSchema.read(file.readText()),
                                )
                            }
                            else -> {
                                LoadedSource.Document(name, file, LocatedDocument.read(file))
                            }
                        }
                } catch (e: Exception) {
                    diagnostics.error(
                        Code.SOURCE_UNREADABLE,
                        "Source '$name' cannot be read: ${e.message}",
                        contractDocument.locate(pointer),
                    )
                }
            }

            session.finish()
            return Sources(loaded)
        }
    }
}

/**
 * The files an SQL source path with `*` or `**` matches (K1.9), in version order: the number after a
 * leading `V` up to `__`, part by part; files without a version follow in code point order.
 */
fun migrations(
    base: Path,
    path: String,
): List<Path> {
    val segments = path.split('/')
    val fixed = segments.takeWhile { '*' !in it }

    val directory = base.resolve(fixed.joinToString("/")).normalize()
    if (!Files.isDirectory(directory)) return emptyList()

    val pattern = ContractFiles.glob(segments.drop(fixed.size).joinToString("/"))
    val files =
        Files.walk(directory).use { stream ->
            stream
                .filter {
                    java.nio.file.Files
                        .isRegularFile(it)
                }.filter { pattern.matches(directory.relativize(it).joinToString("/")) }
                .toList()
        }

    val version = Regex("^V(\\d+(?:[._]\\d+)*)__")

    fun versionOf(file: Path): List<Int>? =
        version
            .find(
                file.fileName.toString(),
            )?.groupValues
            ?.get(1)
            ?.split('.', '_')
            ?.map { it.toInt() }

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
                    a.toString().compareTo(b.toString())
                }
            }
        }

    return files.sortedWith(comparator)
}
