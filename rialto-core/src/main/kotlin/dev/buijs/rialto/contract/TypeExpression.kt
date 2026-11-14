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

/** A parsed type expression of the contract format, such as `decimal(8,3)` or `list<string?>`. */
sealed class TypeExpression {
    data class Scalar(
        val name: String,
        val precision: Int? = null,
        val scale: Int? = null,
    ) : TypeExpression()

    data class Named(
        val name: String,
    ) : TypeExpression()

    data class ListOf(
        val element: TypeExpression,
        val nullableElements: Boolean,
    ) : TypeExpression()

    data class SetOf(
        val element: TypeExpression,
        val nullableElements: Boolean,
    ) : TypeExpression()

    data class MapOf(
        val key: TypeExpression,
        val value: TypeExpression,
        val nullableValues: Boolean,
    ) : TypeExpression()

    /** The named types this expression refers to. */
    fun names(): List<String> =
        when (this) {
            is Scalar -> emptyList()
            is Named -> listOf(name)
            is ListOf -> element.names()
            is SetOf -> element.names()
            is MapOf -> key.names() + value.names()
        }

    companion object {
        val SCALARS =
            setOf(
                "boolean",
                "int32",
                "int64",
                "decimal",
                "float64",
                "string",
                "uuid",
                "date",
                "time",
                "localDateTime",
                "instant",
                "offsetDateTime",
                "zonedDateTime",
                "duration",
                "period",
                "bytes",
                "json",
                "binary",
            )

        /** Scalars that can be the key of a map. */
        val MAP_KEYS = setOf("string", "uuid", "int32", "int64", "date")

        private val DECIMAL = Regex("""decimal\((\d+),(\d+)\)""")
        private val NAME = Regex("[A-Z][A-Za-z0-9]*")
        private val COLLECTION = Regex("""(list|set|map)<(.+)>""")

        /** Parses [text], or returns null when it is not a valid type expression. */
        fun parse(text: String): TypeExpression? {
            val trimmed = text.trim()
            COLLECTION.matchEntire(trimmed)?.let { match ->
                val (kind, arguments) = match.destructured
                return when (kind) {
                    "map" -> {
                        parseMap(arguments)
                    }
                    else -> {
                        val (element, nullable) = parseElement(arguments) ?: return null
                        if (kind == "list") ListOf(element, nullable) else SetOf(element, nullable)
                    }
                }
            }

            return parseSingle(trimmed)
        }

        private fun parseMap(arguments: String): TypeExpression? {
            val comma = arguments.indexOf(',')
            if (comma < 0) return null
            val key = parseSingle(arguments.substring(0, comma).trim()) ?: return null
            if (key is Scalar && key.name !in MAP_KEYS) return null
            val (value, nullable) = parseElement(arguments.substring(comma + 1)) ?: return null
            return MapOf(key, value, nullable)
        }

        private fun parseElement(text: String): Pair<TypeExpression, Boolean>? {
            val trimmed = text.trim()
            val nullable = trimmed.endsWith("?")
            val single = parseSingle(trimmed.removeSuffix("?")) ?: return null
            return single to nullable
        }

        private fun parseSingle(text: String): TypeExpression? {
            DECIMAL.matchEntire(text)?.let { match ->
                val (precision, scale) = match.destructured
                if (scale.toInt() > precision.toInt()) return null
                return Scalar("decimal", precision.toInt(), scale.toInt())
            }

            if (text in SCALARS) return Scalar(text)
            if (NAME.matches(text)) return Named(text)
            return null
        }
    }
}
