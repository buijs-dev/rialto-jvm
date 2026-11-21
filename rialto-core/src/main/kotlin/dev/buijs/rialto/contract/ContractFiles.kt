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

import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.ArrayNode
import tools.jackson.databind.node.ObjectNode
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.isRegularFile
import kotlin.io.path.writeText

/**
 * Reads a contract over several files (K5): the root and the fragments its `include` lists, merged
 * into the bundled form. A contract without `include` is read as it is. Every element keeps the
 * file and line it was read from, so diagnostics name the fragment (K5.5).
 *
 * A large contract can be split over files for its authors, but everything after reading works on one document:
 * the JSON Schema, the model and the checks never know there were several files. Splitting is a matter of
 * authoring, not of meaning. [bundle] writes that one document, for publishing or for tools that read one file.
 */
object ContractFiles {
    /** Sections merged per name; a name is declared in one file only. */
    private val keyed =
        setOf(
            Keys.TYPES,
            Keys.SHAPES,
            Keys.OPERATIONS,
            Keys.EVENTS,
            Keys.SUBSCRIPTIONS,
            Keys.STREAMS,
            Keys.STORAGE,
            Keys.VIEWS,
        )

    /** Keys only the root declares. */
    private val rootOnly = setOf(Keys.INFO, Keys.SOURCES, Keys.INCLUDE)

    /**
     * The bundled form of the contract at [root]. It is [Outcome.Nok] when the root cannot be read, and on any
     * other error, such as an include without a match or a name declared twice.
     */
    fun read(root: Path): Outcome<LocatedDocument> {
        val document =
            when (val read = LocatedDocument.readMapping(root)) {
                is Outcome.Nok -> return read
                is Outcome.Ok -> read.value
            }

        val include =
            document.root.get(Keys.INCLUDE)
                ?: return Outcome.Ok(document)

        // The bundled form drops `include`, so the JSON Schema of the whole contract never sees it: it is checked here.
        val invalid = ContractSchema.check(ContractSchema.include, include, document, "/${Keys.INCLUDE}")
        if (invalid.isNotEmpty()) {
            return Outcome.Nok(invalid)
        }

        val unmatched = mutableListOf<Diagnostic>()
        val matches = matches(root, include)
        matches.forEachIndexed { index, found ->
            if (found.isEmpty()) {
                unmatched +=
                    Diagnostic.error(
                        code = Code.INCLUDE_NO_MATCH,
                        message = "include '${include.get(index).asString()}' matches no file",
                        location = document.locate("/${Keys.INCLUDE}/$index"),
                    )
            }
        }

        val bundle = Bundle(document)
        inReadingOrder(root, matches).forEach { bundle.add(it) }

        return Outcome.of(bundle.document(), unmatched + bundle.diagnostics)
    }

    /**
     * Every file a run over the contract at [root] depends on, for build tools that track their inputs: the root,
     * its `rialto.lock`, its fragments and its local sources, a migrations glob (K1.9) as each file it matches. A
     * remote source is tracked through its hash in the lock (K4). A root that does not exist yields only itself; one
     * that cannot be read yields itself and its lock, so a build still notices when either changes.
     */
    fun inputs(root: Path): List<Path> {
        if (!root.exists()) {
            return listOf(root)
        }

        val base = root.toAbsolutePath().parent
        val lock = base.resolve(RemoteSources.LOCK_FILE)
        val document =
            when (val read = LocatedDocument.readMapping(root)) {
                is Outcome.Nok -> return listOf(root, lock)
                is Outcome.Ok -> read.value
            }

        val sources =
            document.root
                .path(Keys.SOURCES)
                .mapNotNull { it.get(Keys.PATH)?.asString() }
                .filterNot(RemoteSources::isRemote)
                .flatMap { path ->
                    if ('*' in path) {
                        migrations(base, path)
                    } else {
                        listOf(base.resolve(path).normalize())
                    }
                }

        return buildList {
            addAll(listOf(root, lock))
            addAll(fragmentsOf(root, document))
            addAll(sources)
        }
    }

    /** The fragments of the contract at [root]; empty without `include` or when the root cannot be read. */
    internal fun fragments(root: Path): List<Path> =
        when (val read = LocatedDocument.readMapping(root)) {
            is Outcome.Nok -> emptyList()
            is Outcome.Ok -> fragmentsOf(root, read.value)
        }

    /** The fragments the `include` of [document] lists; empty without `include`. */
    private fun fragmentsOf(
        root: Path,
        document: LocatedDocument,
    ): List<Path> {
        val include = document.root.get(Keys.INCLUDE) ?: return emptyList()
        return inReadingOrder(root, matches(root, include))
    }

    /**
     * Writes the bundled form of the contract at [root] to [output] (`rialto bundle`). It is [Outcome.Ok] with
     * [output] when the file was written, and [Outcome.Nok] when the contract has errors; then nothing is written.
     *
     * @throws java.io.IOException when [output] or its directory cannot be written. That is not a finding about the
     *   contract, so it is not part of the outcome.
     */
    fun bundle(
        root: Path,
        output: Path,
    ): Outcome<Path> {
        val read =
            when (val read = read(root)) {
                is Outcome.Nok -> return read
                is Outcome.Ok -> read
            }

        val document = read.value
        val target = output.toAbsolutePath().normalize().parent
        Files.createDirectories(target)

        // Local source paths are relative to the contract, so they follow it to its new place.
        val from = root.toAbsolutePath().normalize().parent
        val bundled = (document.root as ObjectNode).deepCopy()
        bundled.path(Keys.SOURCES).forEach { source ->
            val path = source.path(Keys.PATH).asString()
            if (source is ObjectNode && path.isNotEmpty() && !RemoteSources.isRemote(path)) {
                source.put(Keys.PATH, relativePath(target, from.resolve(path)))
            }
        }

        output.writeText(ContractMapper.yaml.writeValueAsString(bundled))
        return Outcome.Ok(output, read.diagnostics)
    }

    /**
     * What each pattern of [include] matches, in the order of the patterns: the files as paths relative to the
     * directory of [root]. A pattern without a match has an empty list.
     */
    private fun matches(
        root: Path,
        include: JsonNode,
    ): List<List<String>> {
        val base = root.toAbsolutePath().normalize().parent
        val self = root.toAbsolutePath().normalize()
        return include.values().map { pattern ->
            // A fragment lies below the root's directory, and is never the root itself.
            Glob
                .files(base, pattern.asString())
                .filter { it.startsWith(base) && it != self }
                .map { relativePath(base, it) }
        }
    }

    /** The fragments of [matches] in reading order: sorted by relative path, each file once. */
    private fun inReadingOrder(
        root: Path,
        matches: List<List<String>>,
    ): List<Path> {
        val base = root.toAbsolutePath().normalize().parent
        return matches.flatten().toSortedSet().map { base.resolve(it) }
    }

    /**
     * The root with the fragments merged into it, in reading order. Each fragment is checked on its own first
     * (version, root-only keys, fragment schema); then it's named elements join their section, its `mappings`
     * are appended, and any other section may come from one file only. [owner] remembers who declared what, so a
     * name declared twice is reported with the file of its first declaration.
     */
    private class Bundle(
        private val root: LocatedDocument,
    ) {
        private val merged = (root.root as ObjectNode).deepCopy().apply { remove(Keys.INCLUDE) }

        private val positions =
            root.positions
                .filterKeys {
                    it != "/${Keys.INCLUDE}" &&
                        !it.startsWith("/${Keys.INCLUDE}/")
                }.toMutableMap()

        /** Who declared each element of a keyed section (`types/Thing`) and each other top-level key. */
        private val owner = mutableMapOf<String, Path>()

        private val collected = Diagnostics()

        /** Every finding of the fragments added so far, in order. */
        val diagnostics: List<Diagnostic> get() = collected.all

        init {
            merged.propertyNames().forEach { key ->
                if (key in keyed) {
                    merged.get(key).propertyNames().forEach { owner["$key/$it"] = root.file }
                } else if (key != Keys.MAPPINGS) {
                    owner[key] = root.file
                }
            }
        }

        /**
         * Merges the fragment [file] into the bundle. A fragment with a wrong version, a root-only key or a schema
         * error adds nothing; a name it declares twice is left out, the rest is merged.
         */
        fun add(file: Path) {
            val fragment =
                when (val read = LocatedDocument.readMapping(file)) {
                    is Outcome.Nok -> {
                        read.diagnostics.forEach { collected.add(it) }

                        return
                    }
                    is Outcome.Ok -> {
                        read.value
                    }
                }

            val version = fragment.root.path(Keys.RIALTO).asString("")
            val expected = root.root.path(Keys.RIALTO).asString("")
            if (version != expected) {
                collected.error(
                    Code.UNSUPPORTED_VERSION,
                    "Fragment has format '$version', the root '$expected'",
                    fragment.locate("/${Keys.RIALTO}"),
                )
                return
            }

            val misplaced =
                fragment.root
                    .propertyNames()
                    .asSequence()
                    .filter { it in rootOnly }
                    .toList()
            misplaced.forEach { key ->
                collected.error(
                    Code.FRAGMENT_ROOT_KEY,
                    "'$key' belongs in the root, not in a fragment",
                    fragment.locate("/${LocatedDocument.segment(key)}"),
                )
            }

            if (misplaced.isNotEmpty()) {
                return
            }

            val invalid = ContractSchema.check(ContractSchema.fragment, fragment.root, fragment)
            invalid.forEach { collected.add(it) }

            if (invalid.isNotEmpty()) {
                return
            }

            fragment.root.properties().forEach { (key, value) ->
                when (key) {
                    Keys.RIALTO -> Unit
                    in keyed -> section(fragment, key, value)
                    Keys.MAPPINGS -> mappings(fragment, value as ArrayNode)
                    else -> single(fragment, key, value)
                }
            }
        }

        fun document(): LocatedDocument = LocatedDocument(root.file, merged, positions)

        private fun section(
            fragment: LocatedDocument,
            key: String,
            value: JsonNode,
        ) {
            // A section of the root that is no mapping stays as it is: the JSON Schema reports it afterwards, and
            // replacing it by the elements of the fragment would hide the mistake.
            val section =
                when (val existing = merged.get(key)) {
                    null -> merged.putObject(key)
                    is ObjectNode -> existing
                    else -> return
                }

            value.properties().forEach { (name, element) ->
                val pointer = "/$key/${LocatedDocument.segment(name)}"
                val previous = owner["$key/$name"]
                if (previous != null) {
                    collected.error(
                        Code.DUPLICATE_NAME,
                        "'$name' is already declared under $key in ${relative(previous)}",
                        fragment.locate(pointer),
                    )
                    return@forEach
                }

                owner["$key/$name"] = fragment.file
                section.set(name, element)
                copy(fragment, pointer, pointer)
            }
        }

        private fun mappings(
            fragment: LocatedDocument,
            value: ArrayNode,
        ) {
            // As for a section: `mappings` of the root that is no list stays for the JSON Schema to report.
            val mappings =
                when (val existing = merged.get(Keys.MAPPINGS)) {
                    null -> merged.putArray(Keys.MAPPINGS)
                    is ArrayNode -> existing
                    else -> return
                }

            value.forEachIndexed { index, mapping ->
                copy(fragment, "/${Keys.MAPPINGS}/$index", "/${Keys.MAPPINGS}/${mappings.size()}")
                mappings.add(mapping)
            }
        }

        private fun single(
            fragment: LocatedDocument,
            key: String,
            value: JsonNode,
        ) {
            val pointer = "/${LocatedDocument.segment(key)}"
            val previous = owner[key]
            if (previous != null) {
                collected.error(
                    Code.SECTION_TWICE,
                    "'$key' is already declared in ${relative(previous)}; it belongs in one file",
                    fragment.locate(pointer),
                )
                return
            }

            owner[key] = fragment.file
            merged.set(key, value)
            copy(fragment, pointer, pointer)
        }

        /** [file] relative to the root's directory, as a contract author reads it. */
        private fun relative(file: Path): String = relativePath(root.file.toAbsolutePath().parent, file)

        /** Copies the positions of [from] and everything below it in [fragment] to [to] in the bundle. */
        private fun copy(
            fragment: LocatedDocument,
            from: String,
            to: String,
        ) {
            fragment.positions.forEach { (pointer, position) ->
                if (pointer == from || pointer.startsWith("$from/")) {
                    positions[to + pointer.removePrefix(from)] =
                        position
                }
            }
        }
    }
}

/** The path of [file] relative to [directory], with `/` as separator, as contracts and diagnostics write paths. */
fun relativePath(
    directory: Path,
    file: Path,
): String =
    directory
        .toAbsolutePath()
        .normalize()
        .relativize(file.toAbsolutePath().normalize())
        .joinToString("/")
