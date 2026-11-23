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
import tools.jackson.databind.node.ObjectNode
import tools.jackson.module.kotlin.treeToValue

enum class Relation {
    @JsonProperty(Values.OWNED)
    OWNED,

    @JsonProperty(Values.REFERENCE)
    REFERENCE,
}

enum class DefaultPhase {
    @JsonProperty(Values.DECODE)
    DECODE,

    @JsonProperty(Values.BUSINESS)
    BUSINESS,

    @JsonProperty(Values.STORAGE)
    STORAGE,
}

data class Default(
    val value: JsonNode?,
    val phase: DefaultPhase,
)

/**
 * A property of an entity or value object: its [type] as a type expression ([typeExpression]), and the
 * constraints every representation of it shares, on the wire, in storage and in a form. A [Shape] can narrow
 * these constraints for one use, never widen them.
 */
data class Property(
    val type: String,
    val nullable: Boolean = false,
    val minLength: Int? = null,
    val maxLength: Int? = null,
    val minimum: Double? = null,
    val maximum: Double? = null,
    val exclusiveMinimum: Double? = null,
    val exclusiveMaximum: Double? = null,
    val multipleOf: Double? = null,
    val minItems: Int? = null,
    val maxItems: Int? = null,

    /** For a `list<T>`: no two equal elements (D3.8); written only when true. */
    @get:JsonInclude(JsonInclude.Include.NON_DEFAULT)
    val uniqueItems: Boolean = false,

    val pattern: String? = null,
    val format: String? = null,
    /** For a binary: the largest size in bytes, and the media types it may have (K2.11). */
    val maxSize: Long? = null,
    val mediaTypes: List<String> = emptyList(),
    val relation: Relation? = null,
    val default: Default? = null,

    /** Set at creation and never changed afterwards (L1.6); written only when true. */
    @get:JsonInclude(JsonInclude.Include.NON_DEFAULT)
    val immutable: Boolean = false,

    /** Per enum property of the same type, the members while which this property is not null (D3.6). */
    val requiredWhen: Map<String, List<String>> = emptyMap(),

    /** Per enum property of the same type, the members while which this property is null (D3.6). */
    val absentWhen: Map<String, List<String>> = emptyMap(),

    /** Another property of the same type that this one is at least (D3.7). */
    val minimumOf: String? = null,

    /** Another property of the same type that this one is at most (D3.7). */
    val maximumOf: String? = null,

    val description: String? = null,
) {
    /** The parsed [type]; valid once the contract passed validation. */
    @get:JsonIgnore
    val typeExpression: TypeExpression get() =
        requireNotNull(TypeExpression.parse(type)) { "Invalid type '$type'" }
}

/** The state property of an entity and its allowed transitions (L5). */
data class Transitions(
    val property: String,
    val rules: Map<String, TransitionRule>,
)

data class TransitionRule(
    val from: List<String>,
    val to: String,

    /**
     * Per property, what the transition writes with its [to] member (L5.7): `now`, `actor`, or
     * `null` to clear it. Written with its `null` entries, which an empty-value filter would drop.
     */
    @get:JsonInclude(value = JsonInclude.Include.NON_EMPTY, content = JsonInclude.Include.ALWAYS)
    val sets: Map<String, String?> = emptyMap(),

    /** Events published when this rule commits, whichever operation performs it (L5.10). */
    val emits: List<String> = emptyList(),
)

/**
 * The `transition` of an operation (L5): one [rule], written `<Type>.<rule>`, or the rule per value
 * at the JSON Pointer [select] in the request body (L5.6), written `{select, rules}`.
 */
data class TransitionRef(
    val rule: String? = null,
    val select: String? = null,
    val rules: Map<String, String> = emptyMap(),
) {
    /** Every `<Type>.<rule>` the operation can perform. */
    @get:JsonIgnore
    val performed: List<String>
        get() = listOfNotNull(rule) + rules.values.distinct()

    /** The entity of the first rule it performs. */
    @get:JsonIgnore
    val entity: String?
        get() = performed.firstOrNull()?.substringBefore('.')

    @JsonValue
    fun json(): Any = rule ?: mapOf(Keys.SELECT to select, Keys.RULES to rules)

    companion object {
        @JvmStatic
        @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
        fun of(node: JsonNode): TransitionRef =
            if (node.isString) {
                TransitionRef(rule = node.asString())
            } else {
                TransitionRef(
                    select = node.path(Keys.SELECT).takeIf { it.isString }?.asString(),
                    rules =
                        node
                            .path(Keys.RULES)
                            .properties()
                            .associate { (value, rule) -> value to rule.asString() },
                )
            }
    }
}

/**
 * A named type under `types`, the domain model of the contract; the variant follows its `kind`. Every other
 * section refers to these types by name: operations take and return them, storage maps them onto tables,
 * views show them.
 */
sealed class TypeDecl {
    /** A concept with an [identity] that lives over time, such as an order; its state can follow [transitions]. */
    data class Entity(
        val identity: List<String>,
        val aggregateRoot: Boolean = false,
        val transitions: Transitions? = null,
        val invariant: String? = null,
        val properties: Map<String, Property> = emptyMap(),
        val description: String? = null,
    ) : TypeDecl()

    /** A value without identity, equal by its content, such as an address; [wraps] makes it a typed scalar. */
    data class Value(
        val wraps: String? = null,
        val invariant: String? = null,
        val properties: Map<String, Property> = emptyMap(),
        val description: String? = null,
    ) : TypeDecl()

    /** A fixed set of [members]; an [extensible] enum accepts members it does not know yet. */
    data class Enum(
        val members: List<String>,
        val extensible: Boolean = false,
        val description: String? = null,
    ) : TypeDecl()

    /** One of several types, told apart by the value of [discriminator]: per value, the type of that variant. */
    data class Union(
        val discriminator: String,
        val variants: Map<String, String>,
        val description: String? = null,
    ) : TypeDecl()

    /**
     * A read-only view of the type [source] with a selection of its properties, possibly from nested types, such
     * as a summary row of an order.
     *
     * @property paths per projection property its source property path: `{id: id, city:
     *   destination.city}`; the list form `[id, state]` names properties after their source
     * @property listed whether the properties are written as a list; by default when every property
     *   is named after its source
     */
    data class Projection(
        val source: String,

        @get:JsonIgnore
        val paths: Map<String, String>,

        val description: String? = null,

        @get:JsonIgnore
        val listed: Boolean = paths.all { (name, path) -> name == path },
    ) : TypeDecl() {
        /** As written: a list or a map. */
        @get:JsonProperty(Keys.PROPERTIES)
        val properties: Any
            get() = if (listed) paths.keys.toList() else paths

        companion object {
            internal fun of(node: JsonNode): Projection {
                val properties = node.path(Keys.PROPERTIES)
                val paths =
                    if (properties.isArray) {
                        properties.associate { it.asString() to it.asString() }
                    } else {
                        properties.properties().associate { (name, path) ->
                            name to path.asString()
                        }
                    }

                return Projection(
                    source = node.path(Keys.SOURCE).asString(),
                    paths = paths,
                    description = node.path(Keys.DESCRIPTION).takeIf { it.isString }?.asString(),
                    listed = properties.isArray,
                )
            }
        }
    }

    companion object {
        /** Reads a type from its node: the `kind` chooses the variant, the other keys are its fields. */
        @JvmStatic
        @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
        fun of(node: JsonNode): TypeDecl {
            val fields = (node as ObjectNode).deepCopy()
            fields.remove(Keys.KIND)
            val kind = node.path(Keys.KIND).asString()
            if (kind == Values.ENTITY) {
                return ContractMapper.json.treeToValue<Entity>(fields)
            }

            if (kind == Values.VALUE) {
                return ContractMapper.json.treeToValue<Value>(fields)
            }

            if (kind == Values.ENUM) {
                return ContractMapper.json.treeToValue<Enum>(fields)
            }

            if (kind == Values.UNION) {
                return ContractMapper.json.treeToValue<Union>(fields)
            }

            if (kind == Values.PROJECTION) {
                return Projection.of(fields)
            }

            throw IllegalArgumentException("Unknown kind '$kind'")
        }
    }

    /** The `kind` of this type in the contract. */
    @get:JsonProperty(Keys.KIND)
    val kind: String
        get() =
            when (this) {
                is Entity -> Values.ENTITY
                is Value -> Values.VALUE
                is Enum -> Values.ENUM
                is Union -> Values.UNION
                is Projection -> Values.PROJECTION
            }
}

enum class Role {
    @JsonProperty(Values.CALLER)
    CALLER,

    @JsonProperty(Values.APPLICATION)
    APPLICATION,

    @JsonProperty(Values.STORAGE)
    STORAGE,

    @JsonProperty(Values.DERIVED)
    DERIVED,

    @JsonProperty(Values.ACTOR)
    ACTOR,
}

/** How a command carries a property (L1.2): one form, or the list `[identity, command]`. */
enum class As(
    /** As written in the contract, and in diagnostics. */
    val label: String,
) {
    VALUE(Values.VALUE),
    IDENTITY(Values.IDENTITY),
    COMMAND(Values.COMMAND),

    /** `[identity, command]`: a reference a create command links or creates (L1.7). */
    IDENTITY_OR_COMMAND("[identity, command]"),
    ;

    @JsonValue
    fun json(): Any =
        if (this == IDENTITY_OR_COMMAND) {
            listOf(IDENTITY.label, COMMAND.label)
        } else {
            label
        }

    companion object {
        @JvmStatic
        @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
        fun of(node: JsonNode): As {
            // The schema allows only the list of both forms, in either order.
            return if (node.isArray) {
                IDENTITY_OR_COMMAND
            } else {
                entries.first { it.label == node.asString() }
            }
        }
    }
}

/** A shape property; its constraints narrow those of the source property in this shape only (D3.5). */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class ShapeProperty(
    val role: Role,
    val provider: String? = null,

    /** For role actor: the authored seam that derives the value from the verified principal (S4.6). */
    val via: String? = null,

    @JsonProperty(Keys.AS)
    val asForm: As? = null,

    val shape: String? = null,
    val minLength: Int? = null,
    val maxLength: Int? = null,
    val minimum: Double? = null,
    val maximum: Double? = null,
    val exclusiveMinimum: Double? = null,
    val exclusiveMaximum: Double? = null,
    val multipleOf: Double? = null,
    val minItems: Int? = null,
    val maxItems: Int? = null,

    /** For a `list<T>`: no two equal elements in this shape (D3.8); null unless true. */
    val uniqueItems: Boolean? = null,

    val pattern: String? = null,
    val format: String? = null,
)

enum class Command {
    @JsonProperty(Values.CREATE)
    CREATE,

    @JsonProperty(Values.UPDATE)
    UPDATE,
}

enum class UpdateStrategy {
    @JsonProperty(Values.MERGE_PATCH)
    MERGE_PATCH,

    @JsonProperty(Values.REPLACE)
    REPLACE,
}

/**
 * A shape under `shapes`: the form a type takes for one use, such as the input of a create command. It names
 * its [source] type and per property its [ShapeProperty.role] (who provides the value) and narrower constraints.
 * This way one entity can have a 'create', an 'update' and a 'read' form without declaring its properties again.
 */
data class Shape(
    val source: String,
    val partial: Boolean = false,
    val command: Command? = null,
    val strategy: UpdateStrategy? = null,
    val properties: Map<String, ShapeProperty> = emptyMap(),
    val description: String? = null,
)
