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

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonValue
import tools.jackson.databind.JsonNode

data class Converter(
    val toTarget: String? = null,
    val toSource: String? = null,
    val nulls: String? = null,
    val scope: String? = null,
)

/** The source of a mapped property: a path, or a path per form of `[identity, command]` (L1.7). */
sealed class PropertyFrom {
    data class Path(
        @get:JsonValue
        val path: String,
    ) : PropertyFrom()

    data class Forms(
        val identity: String,
        val command: String,
    ) : PropertyFrom()

    /** Every source path: one, or that of each form. */
    @get:JsonIgnore
    val paths: List<String> get() =
        when (this) {
            is Path -> listOf(path)
            is Forms -> listOf(identity, command)
        }

    companion object {
        @JvmStatic
        @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
        fun of(node: JsonNode): PropertyFrom =
            if (node.isString) {
                Path(node.asString())
            } else {
                Forms(node.path(Keys.IDENTITY).asString(), node.path(Keys.COMMAND).asString())
            }
    }
}

data class PropertyMapping(
    val from: PropertyFrom? = null,

    /** Only ever written as `true` (the schema has no `custom: false`). */
    @get:JsonInclude(JsonInclude.Include.NON_DEFAULT)
    val custom: Boolean = false,

    val converter: Converter? = null,
    val members: Map<String, String> = emptyMap(),
    val fallback: String? = null,
    val nullAs: String? = null,
    val onDuplicate: String? = null,
    /** `stored`: the property carries the stored version of the entity on the other side (M2.9). */
    val version: String? = null,
    /** A boolean flag: true when this source property was present in the input (M3.11). */
    val presence: String? = null,
) {
    /** Whether the property carries the stored version (`version: stored`, M2.9). */
    @get:JsonIgnore
    val storedVersion: Boolean get() = version == "stored"

    /** The path of [from] in its path form; null without [from] or for `{identity, command}`. */
    @get:JsonIgnore
    val fromPath: String? get() = (from as? PropertyFrom.Path)?.path
}

/**
 * A mapping under `mappings` (mapping profile, M): how one type or external schema converts into another, such
 * as a domain type into the schema of an OpenAPI body, property by property. Stating the correspondence in the
 * contract lets it be checked and generated instead of written by hand in each code base.
 */
data class Mapping(
    val source: String,
    val target: String,
    val direction: String,
    val properties: Map<String, PropertyMapping> = emptyMap(),
    val ignore: List<String> = emptyList(),
    val variants: Map<String, String> = emptyMap(),
)
