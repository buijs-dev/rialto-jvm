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

package dev.buijs.rialto.core.model

/**
 * A parsed type expression of the contract format, such as `decimal(8,3)` or `list<string?>` (K2.1).
 *
 * In the document the `type` of a property is a plain string, which the JSON Schema cannot check. This class is
 * the one parser of that small language: [FormatChecks][dev.buijs.rialto.core.validation.FormatChecks] uses it to reject invalid types and find the named types
 * they refer to, and checks and generators use [Property.typeExpression] to reason about a type instead of
 * taking the string apart themselves.
 */
sealed class TypeExpression {
    /** A built-in type from [SCALARS]; only `decimal` carries a [precision] and [scale]. */
    data class Scalar(
        val name: String,
        val precision: Int? = null,
        val scale: Int? = null,
    ) : TypeExpression()

    /** A type declared under `types` of the contract, by its name; whether it exists is checked elsewhere. */
    data class Named(
        val name: String,
    ) : TypeExpression()

    /** `list<T>` or `list<T?>`: ordered, duplicates allowed. */
    data class ListOf(
        val element: TypeExpression,
        val nullableElements: Boolean,
    ) : TypeExpression()

    /** `set<T>` or `set<T?>`: no two equal elements. */
    data class SetOf(
        val element: TypeExpression,
        val nullableElements: Boolean,
    ) : TypeExpression()

    /** `map<K, V>`: the key is one of [MAP_KEYS] or an enum, the value any type, nullable with `?`. */
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
        private val DECIMAL = Regex("""decimal\((\d+),(\d+)\)""")
        private val NAME = Regex("[A-Z][A-Za-z0-9]*")
        private val COLLECTION = Regex("""(list|set|map)<(.+)>""")

        /** Scalars that can be the key of a map. */
        val MAP_KEYS = setOf("string", "uuid", "int32", "int64", "date")

        /** The built-in types of the format (K2), which every generator maps onto a type of its language. */
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

        /**
         * Parses [text], or returns null when it is not a valid type expression (K2.1, `RIALTO-K006`). Collections
         * do not nest: the element, key and value of a collection are a scalar or a named type. The text is taken
         * as written: no space around it or inside, except the one space a map may have after its comma.
         */
        fun parse(text: String): TypeExpression? {
            val collection =
                COLLECTION.matchEntire(text)
                    ?: return parseSingle(text)

            val (kind, arguments) = collection.destructured
            return when (kind) {
                "map" -> parseMap(arguments)
                "list" -> parseList(arguments)
                else -> parseSet(arguments)
            }
        }

        private fun parseList(arguments: String): TypeExpression? {
            val (element, nullable) =
                parseElement(arguments)
                    ?: return null

            return ListOf(element, nullable)
        }

        private fun parseSet(arguments: String): TypeExpression? {
            val (element, nullable) =
                parseElement(arguments)
                    ?: return null

            return SetOf(element, nullable)
        }

        private fun parseMap(arguments: String): TypeExpression? {
            val comma = arguments.indexOf(',')
            if (comma < 0) {
                return null
            }

            val key =
                parseSingle(arguments.substring(0, comma))
                    ?: return null

            if (key is Scalar && key.name !in MAP_KEYS) {
                return null
            }

            val (value, nullable) =
                parseElement(arguments.substring(comma + 1).removePrefix(" "))
                    ?: return null

            return MapOf(key, value, nullable)
        }

        private fun parseElement(text: String): Pair<TypeExpression, Boolean>? {
            val nullable = text.endsWith("?")

            val single =
                parseSingle(text.removeSuffix("?"))
                    ?: return null

            return single to nullable
        }

        private fun parseSingle(text: String): TypeExpression? {
            val decimal = DECIMAL.matchEntire(text)
            return when {
                decimal != null -> parseDecimal(decimal)
                text in SCALARS -> Scalar(text)
                NAME.matches(text) -> Named(text)
                else -> null
            }
        }

        /**
         * `decimal(p,s)` from a [match] of [DECIMAL]. Null when the scale is larger than the precision, or when
         * either has more digits than an integer holds: the JSON Schema allows any number of digits there.
         */
        private fun parseDecimal(match: MatchResult): TypeExpression? {
            val (precisionText, scaleText) = match.destructured
            val precision =
                precisionText.toIntOrNull()
                    ?: return null

            val scale =
                scaleText.toIntOrNull()
                    ?: return null

            return if (scale > precision) {
                null
            } else {
                Scalar("decimal", precision, scale)
            }
        }
    }
}
