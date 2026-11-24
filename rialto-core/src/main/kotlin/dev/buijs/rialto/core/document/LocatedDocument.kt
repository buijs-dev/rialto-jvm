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

package dev.buijs.rialto.core.document

import dev.buijs.rialto.core.diagnostics.Code
import dev.buijs.rialto.core.diagnostics.Diagnostic
import dev.buijs.rialto.core.diagnostics.Location
import dev.buijs.rialto.core.diagnostics.Outcome
import dev.buijs.rialto.core.diagnostics.Severity
import dev.buijs.rialto.core.utilities.JsonPointer
import tools.jackson.core.JsonParser
import tools.jackson.core.JsonToken
import tools.jackson.core.ObjectReadContext
import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.ArrayNode
import tools.jackson.databind.node.JsonNodeFactory
import tools.jackson.databind.node.ObjectNode
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.readText

/** Where an element of a document was read: the file, which differs per fragment (K5.5), and its line and column. */
data class Position(
    val file: Path,
    val line: Int,
    val column: Int,
)

/**
 * A YAML or JSON document as a tree, with the file, line and column of every element, so
 * diagnostics can point at the place a JSON Pointer refers to. For a contract over several files
 * ([ContractFiles][dev.buijs.rialto.core.files.ContractFiles]) [file] is the root and every position keeps the fragment it came from.
 *
 * Jackson's own trees forget where an element was read, and the JSON Schema and the checks report their
 * findings as JSON Pointers. This class keeps both together, so a finding such as `#/types/Order/properties/id`
 * reaches the author as `orders.rialto.yaml:12:7`. Contracts, fragments and OpenAPI and AsyncAPI sources are all
 * read through it.
 */
class LocatedDocument internal constructor(
    val file: Path,
    val root: ObjectNode,
    internal val positions: Map<String, Position>,
) {
    /**
     * The location of [pointer], or of its nearest ancestor with a known position: a missing property is
     * reported at the element that should have it.
     */
    fun locate(pointer: String): Location {
        var current = pointer
        while (true) {
            positions[current]?.let { position ->
                return Location(position.file, position.line, position.column, pointer)
            }

            if (current.isEmpty()) {
                return Location(file, pointer = pointer)
            }

            current = current.substringBeforeLast('/', "")
        }
    }

    /** The element at [pointer], or null when there is none, also when [pointer] is no JSON Pointer (no leading `/`). */
    fun at(pointer: String): JsonNode? =
        if (pointer.isNotEmpty() && !pointer.startsWith("/")) {
            null
        } else {
            root.at(pointer).takeUnless { it.isMissingNode }
        }

    companion object {
        private val nodes = JsonNodeFactory.instance

        /** Reads [file] as JSON when it ends in `.json`, otherwise as YAML, like [parse]. */
        fun read(file: Path): LocatedDocument = parse(file, file.readText())

        /**
         * Parses [text] as the content of [file], which is not read: as JSON when its name ends in `.json`,
         * otherwise as YAML. For a document that is not read from a file.
         *
         * The text holds one document, and a mapping holds each key once. A second document, other content after
         * the first, and a key that is used twice all throw: reading on would silently drop what the author wrote.
         * The document is a mapping at the top, as every contract, fragment and source is: a list or a single value
         * throws too. An empty text is an empty mapping.
         */
        fun parse(
            file: Path,
            text: String,
        ): LocatedDocument {
            val factory =
                if (file.extension.equals("json", ignoreCase = true)) {
                    ContractMapper.jsonFactory
                } else {
                    ContractMapper.yamlFactory
                }

            factory.createParser(ObjectReadContext.empty(), text).use { parser ->
                parser.nextToken()
                    ?: return LocatedDocument(file, nodes.objectNode(), emptyMap())

                val reader = TreeReader(file, parser)
                val root = reader.readValue("")
                require(root is ObjectNode) {
                    val found =
                        when {
                            root.isArray -> "a list"
                            root.isNull -> "null"
                            else -> "a single value"
                        }

                    "The document is not a mapping but $found"
                }

                val after = parser.nextToken()
                require(after == null) {
                    val location = parser.currentTokenLocation()
                    "Only one document is expected, but more follows at line ${location.lineNr}, " +
                        "column ${location.columnNr}"
                }

                return LocatedDocument(file, root, reader.positions)
            }
        }

        /**
         * Reads [file] like [read], as every contract and fragment is read. A file that cannot be read or parsed,
         * and anything other than a mapping, such as a list or a single value, is [Outcome.Nok] with one
         * `RIALTO-K001`.
         */
        fun readMapping(file: Path): Outcome<LocatedDocument> =
            try {
                Outcome.Ok(read(file))
            } catch (e: Exception) {
                unreadable(file, e.message ?: "cannot be read")
            }

        private fun unreadable(
            file: Path,
            message: String,
        ): Outcome.Nok = Outcome.Nok(listOf(Diagnostic(Severity.ERROR, Code.UNREADABLE, message, Location(file))))
    }
}

/**
 * Reads the tree of one document from [parser], and remembers per JSON Pointer where its element was read in
 * [file].
 */
private class TreeReader(
    private val file: Path,
    private val parser: JsonParser,
) {
    private val nodes = JsonNodeFactory.instance

    /** Where each element was read, by its JSON Pointer. */
    val positions = mutableMapOf<String, Position>()

    /** The position of the token the parser is at. */
    private fun position(): Position {
        val location = parser.currentTokenLocation()
        return Position(file, location.lineNr, location.columnNr)
    }

    /** Reads the value the parser is at; [pointer] is where it is in the document. */
    fun readValue(pointer: String): JsonNode {
        // The position of a property is that of its name, which readObject has set already.
        positions.putIfAbsent(pointer, position())
        return when (parser.currentToken()) {
            JsonToken.START_OBJECT -> readObject(pointer)
            JsonToken.START_ARRAY -> readArray(pointer)
            JsonToken.VALUE_STRING -> nodes.stringNode(parser.string)
            JsonToken.VALUE_NUMBER_INT -> nodes.numberNode(parser.bigIntegerValue)
            JsonToken.VALUE_NUMBER_FLOAT -> nodes.numberNode(parser.decimalValue)
            JsonToken.VALUE_TRUE -> nodes.booleanNode(true)
            JsonToken.VALUE_FALSE -> nodes.booleanNode(false)
            else -> nodes.nullNode()
        }
    }

    private fun readObject(pointer: String): ObjectNode {
        val node = nodes.objectNode()
        while (parser.nextToken() == JsonToken.PROPERTY_NAME) {
            val name = parser.currentName()
            val child = "$pointer/${JsonPointer.segment(name)}"
            val position = position()
            require(!node.has(name)) {
                "Duplicate key '$name' at line ${position.line}, column ${position.column}"
            }

            positions[child] = position
            parser.nextToken()
            node.set(name, readValue(child))
        }

        return node
    }

    private fun readArray(pointer: String): ArrayNode {
        val node = nodes.arrayNode()
        var index = 0
        while (parser.nextToken() != JsonToken.END_ARRAY) {
            node.add(readValue("$pointer/${index++}"))
        }

        return node
    }
}
