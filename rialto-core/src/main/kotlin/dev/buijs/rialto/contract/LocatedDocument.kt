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

import com.fasterxml.jackson.core.JsonFactory
import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.core.JsonToken
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.JsonNodeFactory
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
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
 * ([ContractFiles]) [file] is the root and every position keeps the fragment it came from.
 */
class LocatedDocument internal constructor(
    val file: Path,
    val root: JsonNode,
    internal val positions: Map<String, Position>,
) {
    /** The location of [pointer], or of its nearest ancestor with a known position. */
    fun locate(pointer: String): Location {
        var current = pointer
        while (true) {
            positions[current]?.let { position ->
                return Location(position.file, position.line, position.column, pointer)
            }

            if (current.isEmpty()) return Location(file, pointer = pointer)
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

        /** Reads [file] as JSON when it ends in `.json`, otherwise as YAML. */
        fun read(file: Path): LocatedDocument = parse(file, file.readText())

        /**
         * Parses [text] as the content of [file], which is not read: as JSON when its name ends in `.json`,
         * otherwise as YAML. For a document that is not read from a file.
         */
        fun parse(
            file: Path,
            text: String,
        ): LocatedDocument {
            val factory =
                if (file.extension.equals("json", ignoreCase = true)) {
                    JsonFactory()
                } else {
                    YAMLFactory()
                }

            factory.createParser(text).use { parser ->
                val lines = mutableMapOf<String, Pair<Int, Int>>()
                parser.nextToken() ?: return LocatedDocument(file, nodes.objectNode(), emptyMap())
                val root = readValue(parser, "", lines)
                val positions = lines.mapValues { (_, at) -> Position(file, at.first, at.second) }

                return LocatedDocument(file, root, positions)
            }
        }

        /**
         * Reads [file] like [read] and requires a mapping at the top, as every contract and fragment is.
         * Anything else, such as a list or a single value, throws, which callers report as RIALTO-K001.
         */
        fun readMapping(file: Path): LocatedDocument =
            read(file).also { document ->
                require(document.root.isObject) {
                    val found =
                        when {
                            document.root.isArray -> "a list"
                            document.root.isNull -> "null"
                            else -> "a single value"
                        }

                    "The document is not a mapping but $found"
                }
            }

        /** Escapes one JSON Pointer segment (RFC 6901). */
        fun segment(name: String): String = name.replace("~", "~0").replace("/", "~1")

        private fun readValue(
            parser: JsonParser,
            pointer: String,
            positions: MutableMap<String, Pair<Int, Int>>,
        ): JsonNode {
            val location = parser.currentTokenLocation()
            positions.putIfAbsent(pointer, location.lineNr to location.columnNr)
            return when (parser.currentToken()) {
                JsonToken.START_OBJECT -> readObject(parser, pointer, positions)
                JsonToken.START_ARRAY -> readArray(parser, pointer, positions)
                JsonToken.VALUE_STRING -> nodes.textNode(parser.text)
                JsonToken.VALUE_NUMBER_INT -> nodes.numberNode(parser.bigIntegerValue)
                JsonToken.VALUE_NUMBER_FLOAT -> nodes.numberNode(parser.decimalValue)
                JsonToken.VALUE_TRUE -> nodes.booleanNode(true)
                JsonToken.VALUE_FALSE -> nodes.booleanNode(false)
                else -> nodes.nullNode()
            }
        }

        private fun readObject(
            parser: JsonParser,
            pointer: String,
            positions: MutableMap<String, Pair<Int, Int>>,
        ): ObjectNode {
            val node = nodes.objectNode()
            while (parser.nextToken() == JsonToken.FIELD_NAME) {
                val name = parser.currentName()
                val child = "$pointer/${segment(name)}"
                val location = parser.currentTokenLocation()
                positions[child] = location.lineNr to location.columnNr
                parser.nextToken()
                node.set<JsonNode>(name, readValue(parser, child, positions))
            }

            return node
        }

        private fun readArray(
            parser: JsonParser,
            pointer: String,
            positions: MutableMap<String, Pair<Int, Int>>,
        ): ArrayNode {
            val node = nodes.arrayNode()
            var index = 0
            while (parser.nextToken() != JsonToken.END_ARRAY) {
                node.add(readValue(parser, "$pointer/${index++}", positions))
            }

            return node
        }
    }
}
