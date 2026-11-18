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

import com.fasterxml.jackson.annotation.JsonAnyGetter
import com.fasterxml.jackson.annotation.JsonAnySetter
import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.annotation.JsonValue
import tools.jackson.databind.JsonNode
import tools.jackson.module.kotlin.convertValue
import tools.jackson.module.kotlin.treeToValue

/**
 * A Rialto contract (contract format 1.0.0). The model mirrors the document one to one; the
 * meaning of each section is defined by the profile that owns it.
 */
data class Contract(
    val rialto: String,
    val info: Info,
    val sources: Map<String, Source> = emptyMap(),
    val auth: Auth? = null,
    val types: Map<String, TypeDecl> = emptyMap(),
    val shapes: Map<String, Shape> = emptyMap(),
    val errors: ErrorModel? = null,
    val operations: Map<String, Operation> = emptyMap(),
    val events: Map<String, Event> = emptyMap(),
    val streams: Map<String, Stream> = emptyMap(),
    val subscriptions: Map<String, Subscription> = emptyMap(),
    val mappings: List<Mapping> = emptyList(),
    val storage: Map<String, Storage> = emptyMap(),
    val views: Map<String, View> = emptyMap(),
    /** The menu of the application (U5.9). */
    val navigation: Navigation? = null,

    /** Top-level extension keys (`x-<generator>-<name>`, K1.8), kept so a written contract still carries them. */
    @param:JsonAnySetter
    @get:JsonAnyGetter
    val extensions: Map<String, Any?> = emptyMap(),
)

data class Info(
    val name: String,
    val version: String,
)

enum class SourceKind {
    @JsonProperty("openapi")
    OPENAPI,

    @JsonProperty("asyncapi")
    ASYNCAPI,

    @JsonProperty("sql")
    SQL,
}

enum class SourceStatus {
    @JsonProperty("current")
    CURRENT,

    @JsonProperty("deprecated")
    DEPRECATED,
}

data class Source(
    val kind: SourceKind,
    val path: String,
    val dialect: String? = null,
    val version: String? = null,
    val status: SourceStatus? = null,
    val deprecated: String? = null,
    val sunset: String? = null,
    val successor: String? = null,
)

// Auth -------------------------------------------------------------------------------------------

data class Auth(
    val providers: Map<String, Provider>,
    val clients: Map<String, Client> = emptyMap(),
    val claims: Claims? = null,
    val scopes: Map<String, ScopeDecl> = emptyMap(),
    val apiKeys: Map<String, ApiKey> = emptyMap(),
)

data class Provider(
    val issuer: String,
    /** Absent means jwt. */
    val verification: Verification? = null,
)

/** How a server verifies access tokens of a provider (S3.1, S6.4). */
enum class Verification {
    @JsonProperty("jwt")
    JWT,

    @JsonProperty("introspection")
    INTROSPECTION,
}

/** An API key of services: its OpenAPI apiKey scheme and the authored lookup (S6). */
data class ApiKey(
    val scheme: String,
    val lookup: String,
)

enum class Flow {
    @JsonProperty("authorizationCode")
    AUTHORIZATION_CODE,

    @JsonProperty("clientCredentials")
    CLIENT_CREDENTIALS,
}

data class Client(
    val provider: String,
    val clientId: String,
    val flow: Flow,
    val scopes: List<String> = emptyList(),
)

data class Claims(
    val subject: String? = null,
    val roles: List<String> = emptyList(),
    val tenant: String? = null,
    val service: String? = null,
)

data class ScopeDecl(
    val description: String? = null,
    val scheme: String,
)

// Domain -----------------------------------------------------------------------------------------

enum class Relation {
    @JsonProperty("owned")
    OWNED,

    @JsonProperty("reference")
    REFERENCE,
}

enum class DefaultPhase {
    @JsonProperty("decode")
    DECODE,

    @JsonProperty("business")
    BUSINESS,

    @JsonProperty("storage")
    STORAGE,
}

data class Default(
    val value: JsonNode?,
    val phase: DefaultPhase,
)

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
) {
    companion object {
        const val NOW: String = "now"
        const val ACTOR: String = "actor"
    }
}

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
    val performed: List<String> get() = listOfNotNull(rule) + rules.values.distinct()

    /** The entity of the first rule it performs. */
    @get:JsonIgnore
    val entity: String? get() = performed.firstOrNull()?.substringBefore('.')

    @JsonValue
    fun json(): Any = rule ?: mapOf("select" to select, "rules" to rules)

    companion object {
        @JvmStatic
        @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
        fun of(node: JsonNode): TransitionRef =
            if (node.isString) {
                TransitionRef(rule = node.asString())
            } else {
                TransitionRef(
                    select = node.path("select").takeIf { it.isString }?.asString(),
                    rules =
                        node
                            .path("rules")
                            .properties()
                            .asSequence()
                            .associate { (value, rule) -> value to rule.asString() },
                )
            }
    }
}

/** A named type; the variant follows its `kind`. */
sealed class TypeDecl {
    data class Entity(
        val identity: List<String>,
        val aggregateRoot: Boolean = false,
        val transitions: Transitions? = null,
        val invariant: String? = null,
        val properties: Map<String, Property> = emptyMap(),
        val description: String? = null,
    ) : TypeDecl()

    data class Value(
        val wraps: String? = null,
        val invariant: String? = null,
        val properties: Map<String, Property> = emptyMap(),
        val description: String? = null,
    ) : TypeDecl()

    data class Enum(
        val members: List<String>,
        val extensible: Boolean = false,
        val description: String? = null,
    ) : TypeDecl()

    data class Union(
        val discriminator: String,
        val variants: Map<String, String>,
        val description: String? = null,
    ) : TypeDecl()

    /**
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
        @get:JsonProperty("properties")
        val properties: Any
            get() = if (listed) paths.keys.toList() else paths

        companion object {
            internal fun of(node: JsonNode): Projection {
                val properties = node.path("properties")
                val paths =
                    if (properties.isArray) {
                        properties.associate { it.asString() to it.asString() }
                    } else {
                        properties.properties().associate { (name, path) ->
                            name to path.asString()
                        }
                    }

                return Projection(
                    source = node.path("source").asString(),
                    paths = paths,
                    description = node.path("description").takeIf { it.isString }?.asString(),
                    listed = properties.isArray,
                )
            }
        }
    }

    companion object {
        @JvmStatic
        @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
        fun of(node: JsonNode): TypeDecl {
            val fields =
                (node as tools.jackson.databind.node.ObjectNode).deepCopy().apply {
                    remove("kind")
                }

            return when (val kind = node.path("kind").asString()) {
                "entity" -> ContractMapper.json.treeToValue<Entity>(fields)
                "value" -> ContractMapper.json.treeToValue<Value>(fields)
                "enum" -> ContractMapper.json.treeToValue<Enum>(fields)
                "union" -> ContractMapper.json.treeToValue<Union>(fields)
                "projection" -> Projection.of(fields)
                else -> throw IllegalArgumentException("Unknown kind '$kind'")
            }
        }
    }

    /** The `kind` of this type in the contract. */
    @get:JsonProperty("kind")
    val kind: String
        get() =
            when (this) {
                is Entity -> "entity"
                is Value -> "value"
                is Enum -> "enum"
                is Union -> "union"
                is Projection -> "projection"
            }
}

enum class Role {
    @JsonProperty("caller")
    CALLER,

    @JsonProperty("application")
    APPLICATION,

    @JsonProperty("storage")
    STORAGE,

    @JsonProperty("derived")
    DERIVED,

    @JsonProperty("actor")
    ACTOR,
}

/** How a command carries a property (L1.2): one form, or the list `[identity, command]`. */
enum class As(
    /** As written in the contract, and in diagnostics. */
    val label: String,
) {
    VALUE("value"),
    IDENTITY("identity"),
    COMMAND("command"),

    /** `[identity, command]`: a reference a create command links or creates (L1.7). */
    IDENTITY_OR_COMMAND("[identity, command]"),
    ;

    @JsonValue
    fun json(): Any = if (this == IDENTITY_OR_COMMAND) listOf("identity", "command") else label

    companion object {
        @JvmStatic
        @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
        fun of(node: JsonNode): As {
            // The schema allows only the list of both forms, in either order.
            if (node.isArray) return IDENTITY_OR_COMMAND
            return entries.first { it.label == node.asString() }
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

    @JsonProperty("as")
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
    @JsonProperty("create")
    CREATE,

    @JsonProperty("update")
    UPDATE,
}

enum class UpdateStrategy {
    @JsonProperty("merge-patch")
    MERGE_PATCH,

    @JsonProperty("replace")
    REPLACE,
}

data class Shape(
    val source: String,
    val partial: Boolean = false,
    val command: Command? = null,
    val strategy: UpdateStrategy? = null,
    val properties: Map<String, ShapeProperty> = emptyMap(),
    val description: String? = null,
)

// Errors -----------------------------------------------------------------------------------------

data class ErrorModel(
    val format: String,
    val validationStatus: Int,
    val types: Map<String, String> = emptyMap(),
    val identify: String,
    /** With `identify: type`: the URI template, with one `{code}`, of every code without its own `type` (E2.2). */
    val codeTypes: String? = null,
    val wire: ErrorWire,
    val codes: Map<String, ErrorCode> = emptyMap(),
) {
    /**
     * The problem `type` of [code] with `identify: type`: its own `type`, else [codeTypes] with
     * the code filled in; `null` with `identify: member`.
     */
    fun typeOf(code: String): String? =
        if (identify != "type") {
            null
        } else {
            codes[code]?.type ?: codeTypes?.replace(CODE_PLACEHOLDER, code)
        }

    companion object {
        /** The placeholder of [codeTypes]. */
        const val CODE_PLACEHOLDER: String = "{code}"
    }
}

/** The standard codes of the errors profile (Standard codes), which application codes must not reuse (E1.1). */
object StandardCodes {
    /** Each standard code with its category. */
    val CATEGORIES: Map<String, String> =
        listOf(
            "required",
            "type",
            "enum",
            "const",
            "oneOf",
            "minLength",
            "maxLength",
            "pattern",
            "format",
            "minimum",
            "maximum",
            "exclusiveMinimum",
            "exclusiveMaximum",
            "multipleOf",
            "minItems",
            "maxItems",
            "uniqueItems",
            "additionalProperties",
            "malformed",
            "precision",
            "invalidQuery",
            "invalidCursor",
            "idempotencyKeyReused",
        ).associateWith { "validation" } +
            mapOf(
                "notFound" to "not-found",
                "unique" to "conflict",
                "reference" to "conflict",
                "invalidTransition" to "conflict",
                "idempotencyKeyInUse" to "conflict",
                "versionMismatch" to "precondition-failed",
                "preconditionRequired" to "precondition-required",
                "maxSize" to "payload-too-large",
                "mediaType" to "unsupported-media-type",
                "actorUnresolved" to "authorization",
            )

    /** The standard code names. */
    val NAMES: Set<String> = CATEGORIES.keys
}

data class ErrorWire(
    val code: String? = null,
    val fieldErrors: String,
    val field: FieldErrorWire,
    val locationFormat: String,
)

data class FieldErrorWire(
    val location: String,
    val code: String,
    val detail: String? = null,
)

data class ErrorCode(
    val category: String,
    val title: String? = null,
    val type: String? = null,
    val status: Int? = null,
    val description: String? = null,
)

// Operations -------------------------------------------------------------------------------------

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
        if (this is Single) {
            listOf(
                ref,
            )
        } else {
            (this as PerSource).refs.values.toList()
        }

    companion object {
        @JvmStatic
        @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
        fun of(node: JsonNode): RestRef =
            if (node.isString) {
                Single(
                    node.asString(),
                )
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
    @param:JsonProperty("transition")
    @get:JsonProperty("transition")
    val transitionRef: TransitionRef? = null,

    /** For a delete: the states in which the entity may be deleted (L5.9). */
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
    val actor: Boolean get() = by == ACTOR

    @JsonValue
    fun json(): Any = if (by == null) type else mapOf("type" to type, "by" to by)

    companion object {
        const val ACTOR: String = "actor"

        @JvmStatic
        @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
        fun of(node: JsonNode): Subject =
            if (node.isString) {
                Subject(node.asString())
            } else {
                Subject(
                    node.path("type").asString(),
                    node.path("by").takeIf { it.isString }?.asString(),
                )
            }
    }
}

/** Which soft-deleted rows a read returns (P6.3). */
enum class DeletedRows {
    @JsonProperty("include")
    INCLUDE,

    @JsonProperty("only")
    ONLY,
}

/** Adds or removes one link of the to-many reference [to], written `<Type>.<property>` (L6). */
data class LinkOperation(
    val to: String,
    val action: LinkAction,
) {
    /** The owner entity of the reference. */
    @get:JsonIgnore
    val owner: String get() = to.substringBefore('.')

    /** The to-many reference property of the owner. */
    @get:JsonIgnore
    val property: String get() = to.substringAfter('.')
}

enum class LinkAction {
    @JsonProperty("add")
    ADD,

    @JsonProperty("remove")
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
 * The sort of a list (Q1.3, Q3.1): the client's sort in [encoding] with a [default], or a [fixed]
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
    val effectiveDirection: String get() = direction ?: ASC

    /** As written: the field name alone, or `{field, direction}`. */
    @JsonValue
    fun json(): Any =
        if (direction == null) {
            field
        } else {
            mapOf("field" to field, "direction" to direction)
        }

    companion object {
        const val ASC: String = "asc"

        @JvmStatic
        @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
        fun of(node: JsonNode): Tiebreaker =
            if (node.isString) {
                Tiebreaker(node.asString())
            } else {
                Tiebreaker(
                    node.path("field").asString(),
                    node.path("direction").takeIf { it.isString }?.asString(),
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

// Events -----------------------------------------------------------------------------------------

data class Event(
    val type: String,
    val source: String,
    val subject: String,
    val data: String,
    val channel: String,
    val mode: String,
    val description: String? = null,
)

data class Stream(
    val events: List<String>,
    val channel: String,
    val transport: String,
    val replay: String,
    val resume: String? = null,
)

data class Delivery(
    val attempts: Int,
    val deadLetter: String,
)

data class Subscription(
    val event: String,
    val handler: String,
    val idempotent: Boolean = false,
    val ordered: Boolean = false,
    val delivery: Delivery,
)

// Mapping ----------------------------------------------------------------------------------------

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
                Forms(node.path("identity").asString(), node.path("command").asString())
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

data class Mapping(
    val source: String,
    val target: String,
    val direction: String,
    val properties: Map<String, PropertyMapping> = emptyMap(),
    val ignore: List<String> = emptyList(),
    val variants: Map<String, String> = emptyMap(),
)

// Storage ----------------------------------------------------------------------------------------

/** A column: a plain name, or a join to another table for a reference. */
sealed class ColumnRef {
    data class Plain(
        @get:JsonValue
        val column: String,
    ) : ColumnRef()

    data class Join(
        val join: String,
        val table: String,
        val key: String,
    ) : ColumnRef()

    /** A join of two steps (P2.11): each step's [JoinStep.column] holds the storage key of a row of its table. */
    data class JoinPath(
        @get:JsonProperty("join")
        val steps: List<JoinStep>,

        val key: String,
    ) : ColumnRef()

    companion object {
        @JvmStatic
        @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
        fun of(node: JsonNode): ColumnRef =
            if (node.isString) {
                Plain(
                    node.asString(),
                )
            } else if (node.path("join").isArray) {
                JoinPath(
                    node.path("join").values().map {
                        JoinStep(it.path("column").asString(), it.path("table").asString())
                    },
                    node.path("key").asString(),
                )
            } else {
                Join(
                    node.path("join").asString(),
                    node.path("table").asString(),
                    node.path("key").asString(),
                )
            }
    }
}

/** One step of a [ColumnRef.JoinPath]: a column of the previous table and the table it leads to. */
data class JoinStep(
    val column: String,
    val table: String,
)

/** A read-only value computed in the read (P2.13): the number of elements of the to-many property [count]. */
data class Computed(
    val count: String,
)

/** Deletion: `none`, `physical`, or soft deletion with a marker column. */
sealed class DeleteMode {
    data object None : DeleteMode()

    data object Physical : DeleteMode()

    /** [by]: the column of the actor who deleted (P6.10). */
    data class Soft(
        val soft: String,
        val restore: Boolean = false,
        val by: String? = null,
    ) : DeleteMode()

    @JsonValue
    fun json(): Any =
        when (this) {
            None -> {
                "none"
            }
            Physical -> {
                "physical"
            }
            is Soft -> {
                listOfNotNull("soft" to soft, by?.let { "by" to it }, "restore" to restore).toMap()
            }
        }

    companion object {
        @JvmStatic
        @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
        fun of(node: JsonNode): DeleteMode =
            when {
                node.isString && node.asString() == "physical" -> {
                    Physical
                }
                node.isString -> {
                    None
                }
                else -> {
                    Soft(
                        soft = node.path("soft").asString(),
                        restore = node.path("restore").asBoolean(false),
                        by = node.path("by").takeIf { it.isString }?.asString(),
                    )
                }
            }
    }
}

/**
 * An owned child: with [parentKey] the child holds the owner's key, with [ownerKey] a to-one owner holds the child's
 * (P4.4). A `list<T>` keeps its order in the position column [order] or sorts by [orderBy] (P4.13).
 */
data class Child(
    val parentKey: String? = null,
    val reconcile: String? = null,
    val nullAs: String? = null,
    val onDelete: String? = null,
    val ownerKey: String? = null,
    /** The integer column that holds the position of each element (P4.14). */
    val order: String? = null,
    /** Entries `<property> asc|desc` of the element type (P4.15). */
    val orderBy: List<String> = emptyList(),
)

/**
 * A to-many reference in storage (P4.11): a link table holding the keys of owner and target, or,
 * without [table] and [targetKey], a read-only reference through the foreign key [ownerKey] in the
 * target's table (P4.12). A `list<T>` keeps its order in the position column [order] of the link
 * table or sorts by [orderBy] (P4.13).
 */
data class Link(
    val table: String? = null,
    val ownerKey: String,
    val targetKey: String? = null,
    val onDelete: String? = null,
    /** The integer column of the link table that holds the position of each element (P4.14). */
    val order: String? = null,
    /** Entries `<property> asc|desc` of the element type (P4.15). */
    val orderBy: List<String> = emptyList(),
) {
    /** Whether the reference is read-only: no link table to write (P4.12). */
    @get:JsonIgnore
    val readOnly: Boolean get() = table == null
}

/**
 * A visibility filter of a stored type (P6.11): every read and write of the type is limited to rows
 * whose [column] holds the value of [source], `tenant` (the verified tenant, S4.2) or `actor` (the
 * verified actor, S4.1).
 */
data class ScopeEntry(
    val column: String,
    val source: String,
) {
    companion object {
        const val TENANT: String = "tenant"
        const val ACTOR: String = "actor"
    }
}

data class Retry(
    val attempts: Int,
    val idempotent: Boolean,
)

data class UnionStorage(
    val strategy: String,
    val discriminator: String,
    val variants: Map<String, Map<String, String>> = emptyMap(),
)

data class Storage(
    val table: String,
    val storageKey: String? = null,
    val columns: Map<String, ColumnRef> = emptyMap(),
    val version: String? = null,
    val generated: Map<String, String> = emptyMap(),
    /** Per property, a read-only count computed in the read (P2.13). */
    val computed: Map<String, Computed> = emptyMap(),
    val children: Map<String, Child> = emptyMap(),
    val links: Map<String, Link> = emptyMap(),
    val delete: DeleteMode? = null,
    /** Visibility filters, each with the source of its value (P6.11). */
    val scope: List<ScopeEntry> = emptyList(),
    val retry: Retry? = null,
    val union: UnionStorage? = null,
    /** Per binary property: the blob store that keeps it and the columns of its key and metadata (P7). */
    val blobs: Map<String, Blob> = emptyMap(),
    /** Lists of properties whose values are unique together (P3.6). */
    val unique: List<List<String>> = emptyList(),
)

data class Blob(
    val store: String,
    val key: String,
    val mediaType: String? = null,
    val size: String? = null,
    val sha256: String? = null,
    val check: String? = null,
)

// Views ------------------------------------------------------------------------------------------

data class Field(
    val edit: String? = null,
    val partial: Boolean = false,
    val hidden: String? = null,
    val disabled: String? = null,
    val component: String? = null,
    val label: String? = null,

    /** When the field is shown (U3.11). */
    @get:JsonInclude(JsonInclude.Include.NON_EMPTY)
    val visibleWhen: Map<String, JsonNode> = emptyMap(),

    /** When the field can be changed (U3.11). */
    @get:JsonInclude(JsonInclude.Include.NON_EMPTY)
    val enabledWhen: Map<String, JsonNode> = emptyMap(),

    /** The view the value links to (U5.11). */
    val link: String? = null,
    /** The elements the user chooses the referenced entity from (U2.11). */
    val options: FieldOptions? = null,
)

/** A choice of elements of [operation], each shown by its [label] path (U2.11). */
data class FieldOptions(
    val operation: String,
    val label: String,
)

data class Action(
    val operation: String,
    val confirm: Boolean = false,
    val pending: String? = null,
    val after: JsonNode? = null,

    /** Per JSON Pointer in the request body, the fixed value the action sends there (U3.9). */
    @get:JsonInclude(value = JsonInclude.Include.NON_EMPTY, content = JsonInclude.Include.ALWAYS)
    val values: Map<String, JsonNode> = emptyMap(),

    /** When the action is shown (U3.11). */
    @get:JsonInclude(JsonInclude.Include.NON_EMPTY)
    val visibleWhen: Map<String, JsonNode> = emptyMap(),

    /** When the action can be triggered (U3.11). */
    @get:JsonInclude(JsonInclude.Include.NON_EMPTY)
    val enabledWhen: Map<String, JsonNode> = emptyMap(),

    /** For a row action: per operation parameter, its source, `route.<name>` or `row.<path>` (U3.10). */
    @get:JsonInclude(JsonInclude.Include.NON_EMPTY)
    val parameters: Map<String, String> = emptyMap(),
)

data class Slot(
    val context: String,
    val mode: String,
)

data class ListView(
    val operation: String,
    val columns: List<String> = emptyList(),
    val filters: Map<String, FilterControl> = emptyMap(),
    val pageSize: Int? = null,
    /** Filters the view always sends, with the actor as value (U6.5). */
    val where: List<FixedFilter> = emptyList(),
    /** Actions offered on each row (U3.10). */
    val actions: Map<String, Action> = emptyMap(),
    /** The view a row opens (U5.11). */
    val open: String? = null,
)

/** A filter a list view always sends: [field] [operator] the signed-in actor (U6.5). */
data class FixedFilter(
    val field: String,
    val operator: String,
    val value: String,
)

data class FilterControl(
    val field: String,
    val operator: String,
)

data class View(
    val route: String? = null,
    val load: String? = null,
    val submit: String? = null,
    val fields: Map<String, Field> = emptyMap(),
    val actions: Map<String, Action> = emptyMap(),
    /** Views shown as tabs of this view, in order (U5.10). */
    val tabs: List<String> = emptyList(),
    val slots: Map<String, Slot> = emptyMap(),
    val dirty: Map<String, Boolean> = emptyMap(),
    val replaceable: Boolean = false,
    val refreshOn: List<String> = emptyList(),
    val list: ListView? = null,
    val requires: List<String> = emptyList(),
)

/** The menu of the application (U5.9): the view shown first and the entries of both menus. */
data class Navigation(
    val home: String? = null,
    val primary: List<NavigationEntry> = emptyList(),
    val secondary: List<NavigationEntry> = emptyList(),
) {
    /** Both menus by their key, `primary` first. */
    @get:JsonIgnore
    val menus: List<Pair<String, List<NavigationEntry>>> get() =
        listOf("primary" to primary, "secondary" to secondary)
}

/** One menu entry: a [view], its [icon] and [label] references, and a count operation as [badge]. */
data class NavigationEntry(
    val view: String,
    val icon: String? = null,
    val label: String? = null,
    val badge: String? = null,
)
