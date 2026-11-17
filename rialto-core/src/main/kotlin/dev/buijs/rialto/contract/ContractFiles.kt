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

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.networknt.schema.JsonSchema
import com.networknt.schema.JsonSchemaFactory
import com.networknt.schema.PathType
import com.networknt.schema.SchemaValidatorsConfig
import com.networknt.schema.SpecVersion
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isRegularFile
import kotlin.io.path.writeText

/**
 * Reads a contract over several files (K5): the root and the fragments its `include` lists, merged
 * into the bundled form. A contract without `include` is read as it is. Every element keeps the
 * file and line it was read from, so diagnostics name the fragment (K5.5).
 */
object ContractFiles {
    /** Sections merged per name; a name is declared in one file only. */
    private val keyed =
        setOf(
            "types",
            "shapes",
            "operations",
            "events",
            "subscriptions",
            "streams",
            "storage",
            "views",
        )

    /** Keys only the root declares. */
    private val rootOnly = setOf("info", "sources", "include")

    private val contractSchema: JsonNode by lazy {
        requireNotNull(
            ContractFiles::class.java.getResourceAsStream("contract-1.0.0.schema.json"),
        ) {
            "Schema resource missing"
        }.use { ContractMapper.json.readTree(it) }
    }

    private val schemaConfig: SchemaValidatorsConfig =
        SchemaValidatorsConfig
            .builder()
            .formatAssertionsEnabled(true)
            .pathType(PathType.JSON_POINTER)
            .build()

    /** The schema of the root's `include` (K5.2). */
    private val includeSchema: JsonSchema by lazy {
        val include =
            contractSchema.path("properties").path("include").deepCopy<ObjectNode>().apply {
                put("\$schema", contractSchema.path("\$schema").asText())
            }

        JsonSchemaFactory
            .getInstance(
                SpecVersion.VersionFlag.V202012,
            ).getSchema(include, schemaConfig)
    }

    private val fragmentSchema: JsonSchema by lazy {
        val schema = contractSchema

        val fragment =
            ContractMapper.json.createObjectNode().apply {
                set<JsonNode>("\$defs", schema.get("\$defs"))
                set<JsonNode>("properties", schema.get("properties"))
                put("\$ref", "#/\$defs/fragment")
            }

        JsonSchemaFactory
            .getInstance(
                SpecVersion.VersionFlag.V202012,
            ).getSchema(fragment, schemaConfig)
    }

    /**
     * The bundled form of the contract at [root], or null when the root cannot be read. Findings,
     * such as an include without a match or a name declared twice, go to [diagnostics].
     */
    fun read(
        root: Path,
        diagnostics: Diagnostics,
    ): LocatedDocument? {
        val document =
            try {
                LocatedDocument.readMapping(root)
            } catch (e: Exception) {
                diagnostics.error(Code.UNREADABLE, e.message ?: "cannot be read", Location(root))
                return null
            }

        val include = document.root.get("include") ?: return document
        // The bundled form drops `include`, so the JSON Schema of the whole contract never sees it: it is checked here.
        val invalid = includeSchema.validate(include)
        invalid.forEach { message ->
            diagnostics.error(
                Code.SCHEMA,
                message.message.substringAfter(": ", message.message),
                document.locate("/include" + pointer(message.instanceLocation.toString())),
            )
        }

        if (invalid.isNotEmpty()) return document
        val bundle = Bundle(document)
        fragments(root, include, document, diagnostics).forEach { bundle.add(it, diagnostics) }

        return bundle.document()
    }

    /** The fragments of the contract at [root], for build tools that track their inputs; empty without `include`. */
    fun fragments(root: Path): List<Path> {
        val document = runCatching { LocatedDocument.read(root) }.getOrNull() ?: return emptyList()
        val include = document.root.get("include") ?: return emptyList()
        return fragments(root, include, document, Diagnostics())
    }

    /** Writes the bundled form of the contract at [root] to [output] (`rialto bundle`); false when it has errors. */
    fun bundle(
        root: Path,
        output: Path,
        diagnostics: Diagnostics,
    ): Boolean {
        val document = read(root, diagnostics) ?: return false
        if (diagnostics.hasErrors) return false
        val target = output.toAbsolutePath().normalize().parent
        Files.createDirectories(target)

        // Local source paths are relative to the contract, so they follow it to its new place.
        val from = root.toAbsolutePath().normalize().parent
        val bundled = (document.root as ObjectNode).deepCopy()
        bundled.path("sources").forEach { source ->
            val path = source.path("path").asText()
            if (source is ObjectNode && path.isNotEmpty() && !RemoteSources.isRemote(path)) {
                source.put(
                    "path",
                    target.relativize(from.resolve(path).normalize()).joinToString("/"),
                )
            }
        }

        output.writeText(ContractMapper.yaml.writeValueAsString(bundled))
        return true
    }

    private fun fragments(
        root: Path,
        include: JsonNode,
        document: LocatedDocument,
        diagnostics: Diagnostics,
    ): List<Path> {
        val base = root.toAbsolutePath().normalize().parent
        val self = root.toAbsolutePath().normalize()
        val candidates =
            Files.walk(base).use { files ->
                files
                    .filter { it.isRegularFile() && it != self }
                    .map { base.relativize(it).joinToString("/") }
                    .toList()
            }

        val matched = sortedSetOf<String>()
        include.forEachIndexed { index, pattern ->
            val regex = glob(pattern.asText())
            val found = candidates.filter { regex.matches(it) }

            if (found.isEmpty()) {
                diagnostics.error(
                    Code.INCLUDE_NO_MATCH,
                    "include '${pattern.asText()}' matches no file",
                    document.locate("/include/$index"),
                )
            }

            matched += found
        }

        return matched.map { base.resolve(it) }
    }

    /**
     * A path pattern of `include`: `*` within one segment, `**` any number of segments, also none. A final `**`
     * matches every file below its directory.
     */
    internal fun glob(pattern: String): Regex {
        val segments = pattern.split('/')
        val regex =
            segments.withIndex().joinToString("") { (index, segment) ->
                val last = index == segments.lastIndex
                when {
                    segment == "**" && last -> {
                        "(?:[^/]+/)*[^/]+"
                    }
                    segment == "**" -> {
                        "(?:[^/]+/)*"
                    }
                    else -> {
                        segment.split('*').joinToString("[^/]*") { Regex.escape(it) } +
                            if (last) "" else "/"
                    }
                }
            }

        return Regex(regex)
    }

    /** The JSON Pointer of a schema finding: the root as the empty pointer. */
    private fun pointer(location: String): String =
        location.takeUnless { it == "/" || it == "$" } ?: ""

    /** The root with the fragments merged into it, in reading order. */
    private class Bundle(
        private val root: LocatedDocument,
    ) {
        private val merged = (root.root as ObjectNode).deepCopy().apply { remove("include") }

        private val positions =
            root.positions
                .filterKeys {
                    it != "/include" &&
                        !it.startsWith("/include/")
                }.toMutableMap()

        /** Who declared each element of a keyed section (`types/Thing`) and each other top-level key. */
        private val owner = mutableMapOf<String, Path>()

        init {
            merged.fieldNames().forEach { key ->
                if (key in keyed) {
                    merged.get(key).fieldNames().forEach { owner["$key/$it"] = root.file }
                } else if (key != "mappings") {
                    owner[key] = root.file
                }
            }
        }

        fun add(
            file: Path,
            diagnostics: Diagnostics,
        ) {
            val fragment =
                try {
                    LocatedDocument.readMapping(file)
                } catch (e: Exception) {
                    diagnostics.error(
                        Code.UNREADABLE,
                        e.message ?: "cannot be read",
                        Location(file),
                    )
                    return
                }

            val version = fragment.root.path("rialto").asText("")
            val expected = root.root.path("rialto").asText("")
            if (version != expected) {
                diagnostics.error(
                    Code.UNSUPPORTED_VERSION,
                    "Fragment has format '$version', the root '$expected'",
                    fragment.locate("/rialto"),
                )
                return
            }

            val misplaced =
                fragment.root
                    .fieldNames()
                    .asSequence()
                    .filter { it in rootOnly }
                    .toList()
            misplaced.forEach { key ->
                diagnostics.error(
                    Code.FRAGMENT_ROOT_KEY,
                    "'$key' belongs in the root, not in a fragment",
                    fragment.locate("/${LocatedDocument.segment(key)}"),
                )
            }

            if (misplaced.isNotEmpty()) return
            val invalid = fragmentSchema.validate(fragment.root)
            invalid.forEach { message ->
                diagnostics.error(
                    Code.SCHEMA,
                    message.message.substringAfter(": ", message.message),
                    fragment.locate(pointer(message.instanceLocation.toString())),
                )
            }

            if (invalid.isNotEmpty()) return
            fragment.root.fields().forEach { (key, value) ->
                when (key) {
                    "rialto" -> Unit
                    in keyed -> section(fragment, key, value, diagnostics)
                    "mappings" -> mappings(fragment, value as ArrayNode)
                    else -> single(fragment, key, value, diagnostics)
                }
            }
        }

        fun document(): LocatedDocument = LocatedDocument(root.file, merged, positions)

        private fun section(
            fragment: LocatedDocument,
            key: String,
            value: JsonNode,
            diagnostics: Diagnostics,
        ) {
            val section = merged.get(key) as? ObjectNode ?: merged.putObject(key)
            value.fields().forEach { (name, element) ->
                val pointer = "/$key/${LocatedDocument.segment(name)}"
                val previous = owner["$key/$name"]
                if (previous != null) {
                    diagnostics.error(
                        Code.DUPLICATE_NAME,
                        "'$name' is already declared under $key in ${relative(previous)}",
                        fragment.locate(pointer),
                    )
                    return@forEach
                }

                owner["$key/$name"] = fragment.file
                section.set<JsonNode>(name, element)
                copy(fragment, pointer, pointer)
            }
        }

        private fun mappings(
            fragment: LocatedDocument,
            value: ArrayNode,
        ) {
            val mappings = merged.get("mappings") as? ArrayNode ?: merged.putArray("mappings")
            value.forEachIndexed { index, mapping ->
                copy(fragment, "/mappings/$index", "/mappings/${mappings.size()}")
                mappings.add(mapping)
            }
        }

        private fun single(
            fragment: LocatedDocument,
            key: String,
            value: JsonNode,
            diagnostics: Diagnostics,
        ) {
            val pointer = "/${LocatedDocument.segment(key)}"
            val previous = owner[key]
            if (previous != null) {
                diagnostics.error(
                    Code.SECTION_TWICE,
                    "'$key' is already declared in ${relative(previous)}; it belongs in one file",
                    fragment.locate(pointer),
                )
                return
            }

            owner[key] = fragment.file
            merged.set<JsonNode>(key, value)
            copy(fragment, pointer, pointer)
        }

        /** [file] relative to the root's directory, as a contract author reads it. */
        private fun relative(file: Path): String =
            root.file
                .toAbsolutePath()
                .normalize()
                .parent
                .relativize(
                    file.toAbsolutePath().normalize(),
                ).joinToString("/")

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
