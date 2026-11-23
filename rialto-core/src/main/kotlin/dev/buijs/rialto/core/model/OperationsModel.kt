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

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.annotation.JsonValue
import dev.buijs.rialto.core.document.ContractMapper
import tools.jackson.databind.JsonNode
import tools.jackson.module.kotlin.convertValue

/** A REST reference: one operation, or one per API source. */
sealed class RestRef {
    data class Single(
        @get:JsonValue
        val ref: String,
    ) : RestRef()

    data class PerSource(
        @get:JsonValue
        val refs: Map<String, String>,
    ) : RestRef()

    /** Every reference, whatever the form. */
    @get:JsonIgnore
    val all: List<String> get() =
        when (this) {
            is Single -> listOf(ref)
            is PerSource -> refs.values.toList()
        }

    companion object {
        @JvmStatic
        @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
        fun of(node: JsonNode): RestRef =
            if (node.isString) {
                Single(node.asString())
            } else {
                PerSource(ContractMapper.json.convertValue(node))
            }
    }
}

data class Precondition(
    val token: String,
    val version: String,
)

/** A read answered with 304 while the client has the current representation (R6). */
data class Conditional(
    val token: String,
    val version: String,
)

data class Deprecation(
    val since: String,
    val sunset: String? = null,
    val successor: String? = null,
)

/**
 * An operation under `operations`: one thing a client can ask of the service, such as creating an order or
 * listing orders. It ties the HTTP operation in the OpenAPI source ([rest]) to the domain: what goes in
 * ([input]) and comes out ([output]), which entity it addresses ([subject]), and what it does to it
 * ([lifecycle], [transitionRef], [link]). Most properties belong to one profile and are checked by it.
 */
data class Operation(
    val rest: RestRef? = null,
    val input: String? = null,
    /**
     * The type or shape of the response, or `list<T>` for a bare, unpaged array (R2.7), as written.
     * [outputType] is the type or shape it names in both forms.
     */
    val output: String? = null,
    /** For `output: list<T>`: the most elements the response holds (R2.7). */
    val maxItems: Int? = null,
    val precondition: Precondition? = null,
    val conditional: Conditional? = null,
    val handler: String? = null,
    val list: ListSpec? = null,
    /** Per OpenAPI path or query parameter, the property path it addresses (R1.5). */
    val parameters: Map<String, String> = emptyMap(),
    val errors: List<String> = emptyList(),
    /** `create`, `update`, `delete` or `restore` (L3). */
    val lifecycle: String? = null,
    /**
     * The entity the operation addresses (R1.7), or with `by: actor` the entity of the verified
     * principal (R1.8).
     */
    val subject: Subject? = null,
    /** Reads soft-deleted rows explicitly: `include` (live and deleted) or `only` (P6.3). */
    val deleted: DeletedRows? = null,
    val idempotency: Idempotency? = null,
    val batch: Batch? = null,

    /**
     * The transition this operation performs (L5): `<Type>.<rule>`, or a select by a value in the
     * request body (L5.6). [transition] is the single rule.
     */
    @param:JsonProperty(Keys.TRANSITION)
    @get:JsonProperty(Keys.TRANSITION)
    val transitionRef: TransitionRef? = null,

    /** For a 'delete': the states in which the entity may be deleted (L5.9). */
    val from: List<String> = emptyList(),
    /** The one link this operation adds or removes (L6). */
    val link: LinkOperation? = null,
    val emits: List<String> = emptyList(),
    val deprecated: Deprecation? = null,
    val principals: List<String> = emptyList(),
    val policy: String? = null,
    val description: String? = null,
) {
    /** The single rule this operation performs, as `<Type>.<rule>`; null for a select (L5.6). */
    @get:JsonIgnore
    val transition: String? get() = transitionRef?.rule

    /** The type or shape [output] names: `T` of `list<T>`, else [output] itself. */
    @get:JsonIgnore
    val outputType: String? get() = output?.let { listElementOf(it) ?: it }

    /** Whether [output] is `list<T>`, a bare, unpaged array of [outputType] (R2.7). */
    @get:JsonIgnore
    val outputList: Boolean get() = output?.let(::listElementOf) != null

    /** [output] when it is one value, not `list<T>`. */
    @get:JsonIgnore
    val singleOutput: String? get() = output?.takeUnless { outputList }

    private companion object {
        /** `T` of `list<T>` with a named `T`, or null for anything else. */
        fun listElementOf(text: String): String? {
            val list = TypeExpression.parse(text) as? TypeExpression.ListOf ?: return null
            return (list.element as? TypeExpression.Named)?.name?.takeUnless {
                list.nullableElements
            }
        }
    }
}

/**
 * The `subject` of an operation (R1.7): an entity [type], written as its name, or with [by]
 * `actor` the entity whose identity the verified principal gives (R1.8), written `{type, by}`.
 */
data class Subject(
    val type: String,
    val by: String? = null,
) {
    /** Whether the identity comes from the verified principal (`by: actor`, R1.8). */
    @get:JsonIgnore
    val actor: Boolean
        get() = by == Values.ACTOR

    @JsonValue
    fun json(): Any =
        if (by == null) {
            type
        } else {
            mapOf(Keys.TYPE to type, Keys.BY to by)
        }

    companion object {
        @JvmStatic
        @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
        fun of(node: JsonNode): Subject =
            if (node.isString) {
                Subject(node.asString())
            } else {
                Subject(
                    type = node.path(Keys.TYPE).asString(),
                    by = node.path(Keys.BY).takeIf { it.isString }?.asString(),
                )
            }
    }
}

/** Which soft-deleted rows a read returns (P6.3). */
enum class DeletedRows {
    @JsonProperty(Values.INCLUDE)
    INCLUDE,

    @JsonProperty(Values.ONLY)
    ONLY,
}

/** Adds or removes one link of the to-many reference [to], written `<Type>.<property>` (L6). */
data class LinkOperation(
    val to: String,
    val action: LinkAction,
) {
    /** The owner entity of the reference. */
    @get:JsonIgnore
    val owner: String
        get() = to.substringBefore('.')

    /** The to-many reference property of the owner. */
    @get:JsonIgnore
    val property: String
        get() = to.substringAfter('.')
}

enum class LinkAction {
    @JsonProperty(Values.ADD)
    ADD,

    @JsonProperty(Values.REMOVE)
    REMOVE,
}

data class Idempotency(
    val header: String,
)

/**
 * A batch operation (L4). [wire] holds JSON Pointers per role: `items` in the request, `results` in
 * the response, and `value`, `problem`, `index` and `status` in one result; `""` is the body itself.
 */
data class Batch(
    val atomic: Boolean,
    val maxItems: Int? = null,

    /** Written with its `""` pointers, which an empty-value filter would drop. */
    @get:JsonInclude(value = JsonInclude.Include.NON_EMPTY, content = JsonInclude.Include.ALWAYS)
    val wire: Map<String, String>,
) {
    companion object {
        /** The roles of [wire] that point into an element of `results` (L4.5). */
        val RESULT_ROLES: List<String> = listOf("value", "problem", "index", "status")
    }
}

data class ListSpec(
    val fields: Map<String, QueryField>,
    val filter: FilterSpec? = null,
    val sort: SortSpec? = null,
    val pagination: Pagination,
    val count: CountSpec? = null,
    /** Absent for a local list, an operation without `rest` (Q1.6). */
    val wire: ListWire? = null,
)

data class QueryField(
    val path: String? = null,
    val filter: List<String> = emptyList(),
    val sort: Boolean = false,
)

data class FilterSpec(
    val encoding: String? = null,
    val maxLength: Int? = null,
    val maxDepth: Int? = null,
)

/**
 * The 'sort' of a list (Q1.3, Q3.1): the client's sort in [encoding] with a [default], or a [fixed]
 * order the client cannot change; the [tiebreaker] follows either.
 */
data class SortSpec(
    val encoding: String? = null,
    val default: List<SortKey> = emptyList(),
    val tiebreaker: Tiebreaker,
    val fixed: List<SortKey> = emptyList(),
)

/** The tiebreaker of a sort: a field, ascending when it declares no [direction] (Q1.3). */
data class Tiebreaker(
    val field: String,
    val direction: String? = null,
) {
    /** [direction], or `asc` when it is absent (Q1.3). */
    @get:JsonIgnore
    val effectiveDirection: String get() = direction ?: Values.ASC

    /** As written: the field name alone, or `{field, direction}`. */
    @JsonValue
    fun json(): Any =
        if (direction == null) {
            field
        } else {
            mapOf(Keys.FIELD to field, Keys.DIRECTION to direction)
        }

    companion object {
        @JvmStatic
        @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
        fun of(node: JsonNode): Tiebreaker =
            if (node.isString) {
                Tiebreaker(node.asString())
            } else {
                Tiebreaker(
                    field = node.path(Keys.FIELD).asString(),
                    direction = node.path(Keys.DIRECTION).takeIf { it.isString }?.asString(),
                )
            }
    }
}

data class SortKey(
    val field: String,
    val direction: String,
)

data class Pagination(
    val strategy: String,
    val limit: Limit,
)

data class Limit(
    val default: Int,
    val max: Int,
)

data class CountSpec(
    val strategy: String,
    val response: String? = null,
    val operation: String? = null,
)

data class ListWire(
    val parameters: Map<String, String>,
    val response: Map<String, String>,
)
