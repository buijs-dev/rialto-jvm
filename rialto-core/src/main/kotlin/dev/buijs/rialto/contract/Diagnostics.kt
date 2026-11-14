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

import java.nio.file.Path

/** Whether a finding fails the run. */
enum class Severity { ERROR, WARNING }

/** Where a finding was made: a file, a position in it when known, and a JSON Pointer. */
data class Location(
    val file: Path,
    val line: Int? = null,
    val column: Int? = null,
    val pointer: String = "",
) {
    override fun toString(): String {
        val position = if (line != null) ":$line:${column ?: 1}" else ""
        val path = if (pointer.isNotEmpty()) " #$pointer" else ""
        return "${file.fileName}$position$path"
    }
}

/**
 * The diagnostic codes of Rialto tooling, shared by every implementation (see docs/diagnostics.md).
 * Each code names the specification requirement it enforces, when there is one.
 */
enum class Code(
    val id: String,
    val requirement: String?,
    val summary: String,
) {
    UNREADABLE(
        "RIALTO-K001",
        "K1.7",
        "contract or fragment cannot be read or parsed, or is not a mapping",
    ),
    SCHEMA("RIALTO-K002", "K1.7", "contract is not valid against the JSON Schema"),
    UNSUPPORTED_VERSION("RIALTO-K003", "K1.1", "unsupported contract format version"),
    UNRESOLVED_REFERENCE("RIALTO-K004", "K1.2", "reference does not resolve"),
    DUPLICATE_NAME("RIALTO-K005", "K1.3", "name is used twice"),
    TYPE_EXPRESSION("RIALTO-K006", "K2.1", "invalid type expression"),
    INCLUDE_NO_MATCH("RIALTO-K007", "K5.2", "include path matches no file"),
    FRAGMENT_ROOT_KEY("RIALTO-K008", "K5.2", "fragment declares info, sources or include"),
    SECTION_TWICE("RIALTO-K009", "K5.4", "section that belongs in one file appears in two"),
    SOURCE_UNREADABLE("RIALTO-S001", "K1.9", "referenced source cannot be read"),
    SOURCE_REFERENCE("RIALTO-S002", "K1.2", "reference into a source does not resolve"),
    REMOTE_UNAVAILABLE(
        "RIALTO-S003",
        "K4.3",
        "remote source can neither be fetched nor found in the cache",
    ),
    REMOTE_CHANGED("RIALTO-S004", "K4.2", "remote source differs from its hash in rialto.lock"),
    REMOTE_SCHEME("RIALTO-S005", "K4.4", "remote source does not use https"),
    MISSING_IDENTITY("RIALTO-P001", "D2.2", "entity identity is not one of its properties"),
    UNION_VARIANTS(
        "RIALTO-P002",
        "D2.6",
        "union variants are not all entities or all value objects",
    ),
    QUERY_FIELD("RIALTO-P003", "Q1.1", "query field or operator does not fit the output type"),
    TIEBREAKER("RIALTO-P004", "Q1.3", "tiebreaker is not a sortable query field"),
    ERROR_CODE("RIALTO-P005", "E1.1", "operation returns an undeclared error code"),
    COMMAND_PROPERTY("RIALTO-P006", "L1.2", "command property does not fit its relationship"),
    SOURCE_VERSION("RIALTO-P007", "V1.4", "source version differs from the document version"),
    UNDECLARED_SCOPE("RIALTO-P008", "S1.1", "scope used by an operation is not declared"),
    LIST_WIRE("RIALTO-P009", "Q1.4", "list wire element does not exist in the OpenAPI operation"),
    ERROR_WIRE("RIALTO-P010", "E1.3", "error wire pointer does not exist in the problem schema"),
    EVENT_SUBJECT("RIALTO-P011", "A1.1", "event subject is not a property of the event data"),
    DISCRIMINATOR(
        "RIALTO-P012",
        "K2.8",
        "a variant declares the discriminator with a type other than string",
    ),
    LIST_KIND(
        "RIALTO-P013",
        "Q1.6",
        "list wire or encodings do not fit a REST or local operation, or a fixed sort takes a " +
            "sort parameter or encoding",
    ),
    SHAPE_CONSTRAINT(
        "RIALTO-P014",
        "D3.5",
        "shape constraint weakens its source property's or does not apply to its type",
    ),
    PARAMETER(
        "RIALTO-P015",
        "R1.5",
        "REST parameter is not mapped, or its mapping does not resolve",
    ),
    CODE_TYPE(
        "RIALTO-P016",
        "E2.2",
        "code types are not one template with {code}, or two codes or categories share a type",
    ),
    DEPRECATION(
        "RIALTO-P017",
        "V2.2",
        "deprecated operation is not marked deprecated in its OpenAPI operation",
    ),
    PATH_LOCATION(
        "RIALTO-P018",
        "E1.5",
        "with locationFormat path, a request body property and a parameter or header share a name",
    ),
    COUNT_FILTER(
        "RIALTO-P019",
        "Q5.2",
        "count operation does not take the list's filter, or requires a parameter the list does not have",
    ),
    CONDITIONAL(
        "RIALTO-P020",
        "R6.1",
        "conditional operation is not a GET or HEAD with If-None-Match, an ETag on success and a 304",
    ),
    BINARY(
        "RIALTO-P021",
        "K2.11",
        "binary without maxSize, in a collection, as a query field, or carried as JSON",
    ),
    API_KEY(
        "RIALTO-P022",
        "S6.1",
        "apiKey scheme of an operation is not declared under auth.apiKeys, or not sent in a header",
    ),
    TRANSITION(
        "RIALTO-P023",
        "L5.1",
        "transition state is not a closed enum with a member default, names a non-member or an " +
            "unknown rule, or a command shape contains it",
    ),
    IDEMPOTENCY(
        "RIALTO-P024",
        "L3.2",
        "idempotency on a safe method, or its header is not a header of the OpenAPI operation",
    ),
    MAPPING_COVERAGE(
        "RIALTO-P025",
        "M2.8",
        "mapping names a property neither side has, leaves a required property uncovered, or " +
            "uses version: stored without a stored version",
    ),
    STORAGE_COLUMN(
        "RIALTO-P026",
        "P1.5",
        "stored property has no or two places, shares a column, or its column type or " +
            "nullability, the type of a join's key column, or the type of an order column, " +
            "does not fit",
    ),
    BATCH_WIRE(
        "RIALTO-P027",
        "L4.5",
        "batch wire pointer does not resolve in the OpenAPI operation, or is not an array, or " +
            "index or status is not an integer",
    ),
    IMMUTABLE("RIALTO-P028", "L1.6", "update command shape has an immutable property"),
    LINK(
        "RIALTO-P029",
        "L6.1",
        "link operation does not name a to-many reference with a link table, does not address " +
            "owner and target by identity, or also declares lifecycle, input or transition",
    ),
    UNIQUE_KEY(
        "RIALTO-P030",
        "P3.6",
        "unique key names a property without a plain column, or an operation addresses an " +
            "entity by properties that are neither its identity nor a unique key",
    ),
    SOFT_DELETE(
        "RIALTO-P031",
        "P6.3",
        "deleted on an operation that does not read or on an entity without soft deletion, a " +
            "delete or restore that names no entity, a restore without restore: true, a subject " +
            "that is not an entity, or a " +
            "command property on the soft-delete marker or by column",
    ),
    REFERENCE_OR_CREATE(
        "RIALTO-P032",
        "L1.7",
        "as: [identity, command] outside a create command, on a property that is not a " +
            "reference, without the create command shape of its type, or its mapping lacks a " +
            "from per form",
    ),
    LIST_OUTPUT(
        "RIALTO-P033",
        "R2.7",
        "output: list<T> lacks maxItems, its success response is not an array of T, or it is " +
            "also a list operation",
    ),
    ACTOR_SUBJECT(
        "RIALTO-P034",
        "R1.8",
        "subject by actor names no entity, maps a parameter to its identity, has an input " +
            "that carries that identity, or belongs to an operation that needs no signed-in " +
            "caller; or an input with role actor belongs to such an operation",
    ),
    SCOPE(
        "RIALTO-P035",
        "P6.11",
        "scope names a column twice or a column the table lacks, storage entries on one table " +
            "declare different scopes, a reference lacks a source of the scope of its target, " +
            "a command carries a property on a scope column, or a type with an actor entry is " +
            "reached by an operation that needs no signed-in caller",
    ),
    TRANSITION_FORM(
        "RIALTO-P036",
        "L5.6",
        "select names rules of several entities or does not resolve to a string in the request " +
            "body, a sets entry does not fit its property, a create performs a rule that does " +
            "not start from the initial state, or from is declared off a delete with transitions",
    ),
    ACTION_VALUES(
        "RIALTO-P037",
        "U3.9",
        "action value pointer does not resolve in the request body, its value does not fit " +
            "the schema there, or it selects no rule",
    ),
    PROPERTY_RULE(
        "RIALTO-P038",
        "D3.6",
        "requiredWhen or absentWhen does not name another enum property and its members, or is " +
            "on a non-nullable property; minimumOf or maximumOf does not name another property " +
            "with the same ordered scalar; or uniqueItems is not on a list",
    ),
    PRESENCE(
        "RIALTO-P039",
        "M3.11",
        "presence in a toSource mapping, on a target property that is not a non-nullable " +
            "boolean, of a property the source lacks, or from a source that does not keep presence",
    ),
    STORAGE_DERIVED(
        "RIALTO-P040",
        "P2.11",
        "join of two steps on a collection, in a command or generated, join without relation " +
            "on a type that is not a scalar or wrapper, or computed count that is not a " +
            "non-nullable integer, is written, or counts no to-many child or link",
    ),
    VIEW_LINK(
        "RIALTO-P041",
        "U5.9",
        "menu entry, tab, link or open names a view whose route the data cannot fill, a view " +
            "twice, or a badge that is not the count operation of a list",
    ),
    LIST_VIEW(
        "RIALTO-P042",
        "U6.5",
        "row action parameter is filled neither from the route nor the row, a where or column " +
            "does not fit the list operation, or the list operation is neither a list nor list<T>",
    ),
    VIEW_CONDITION(
        "RIALTO-P043",
        "U3.11",
        "visibleWhen or enabledWhen path is not an enum property with the listed members, or for " +
            "actor not a string or uuid",
    ),
    FIELD_OPTIONS(
        "RIALTO-P044",
        "U2.11",
        "options field is not a to-one reference of its input, or its operation or label does " +
            "not fit the referenced entity",
    ),
    STORAGE_ORDER(
        "RIALTO-P045",
        "P4.13",
        "list under children or links declares neither or both of order and orderBy, a set " +
            "declares one, or an orderBy entry is not a sortable property of the element type",
    ),
    INTERNAL("RIALTO-X001", null, "internal error"),
}

/** One finding. */
data class Diagnostic(
    val severity: Severity,
    val code: Code,
    val message: String,
    val location: Location,
    val hint: String? = null,
    /** The requirement this finding enforces, when it differs from the first one of [code]. */
    val requirementOverride: String? = null,
) {
    /** The requirement this finding enforces: its own, else the one of [code]. */
    val requirement: String? get() = requirementOverride ?: code.requirement

    /** The code as reported. */
    val id: String get() = code.id

    override fun toString(): String {
        val label = if (severity == Severity.ERROR) "error" else "warning"
        val requirement = requirement?.let { " ($it)" } ?: ""
        val hintLine = hint?.let { "\n  hint: $it" } ?: ""
        return "$label[$id] $location: $message$requirement$hintLine"
    }
}

/** Collects findings during one run. */
class Diagnostics {
    private val items = mutableListOf<Diagnostic>()

    val all: List<Diagnostic> get() = items.toList()

    val hasErrors: Boolean get() = items.any { it.severity == Severity.ERROR }

    fun add(diagnostic: Diagnostic) {
        items += diagnostic
    }

    fun error(
        code: Code,
        message: String,
        location: Location,
        hint: String? = null,
        requirement: String? = null,
    ) {
        items += Diagnostic(Severity.ERROR, code, message, location, hint, requirement)
    }

    fun warning(
        code: Code,
        message: String,
        location: Location,
        hint: String? = null,
    ) {
        items += Diagnostic(Severity.WARNING, code, message, location, hint)
    }
}
