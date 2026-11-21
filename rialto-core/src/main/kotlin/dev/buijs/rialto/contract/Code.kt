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

/**
 * The diagnostic codes of Rialto tooling, shared by every implementation (see docs/diagnostics.md).
 * Each code names the first specification requirement it enforces.
 *
 * The codes are a public interface: every tool reports the same code for the same mistake, CI and editors
 * match on them, and the conformance tests of the specification check them. The prefix tells the kind: `K` the
 * contract format, `S` the sources, `P` the rules of a profile. The tests require this table to equal the
 * standard codes of the specification, so a code is added there first.
 */
enum class Code(
    val id: String,
    val requirement: String,
    val summary: String,
) {
    UNREADABLE(
        id = "RIALTO-K001",
        requirement = "K1.7",
        summary = "contract or fragment cannot be read or parsed, or is not a mapping",
    ),
    SCHEMA(
        id = "RIALTO-K002",
        requirement = "K1.7",
        summary = "contract is not valid against the JSON Schema",
    ),
    UNSUPPORTED_VERSION(
        id = "RIALTO-K003",
        requirement = "K1.1",
        summary = "unsupported contract format version",
    ),
    UNRESOLVED_REFERENCE(
        id = "RIALTO-K004",
        requirement = "K1.2",
        summary = "reference does not resolve",
    ),
    DUPLICATE_NAME(
        id = "RIALTO-K005",
        requirement = "K1.3",
        summary = "name is used twice",
    ),
    TYPE_EXPRESSION(
        id = "RIALTO-K006",
        requirement = "K2.1",
        summary = "invalid type expression",
    ),
    INCLUDE_NO_MATCH(
        id = "RIALTO-K007",
        requirement = "K5.2",
        summary = "include path matches no file",
    ),
    FRAGMENT_ROOT_KEY(
        id = "RIALTO-K008",
        requirement = "K5.2",
        summary = "fragment declares info, sources or include",
    ),
    SECTION_TWICE(
        id = "RIALTO-K009",
        requirement = "K5.4",
        summary = "section that belongs in one file appears in two",
    ),
    LOCK_UNUSABLE(
        id = "RIALTO-K010",
        requirement = "K4.5",
        summary = "rialto.lock cannot be read, or cannot be written when it must change",
    ),
    SOURCE_UNREADABLE(
        id = "RIALTO-S001",
        requirement = "K1.9",
        summary = "referenced source cannot be read",
    ),
    SOURCE_REFERENCE(
        id = "RIALTO-S002",
        requirement = "K1.2",
        summary = "reference into a source does not resolve",
    ),
    REMOTE_UNAVAILABLE(
        id = "RIALTO-S003",
        requirement = "K4.3",
        summary = "remote source can neither be fetched nor found in the cache",
    ),
    REMOTE_CHANGED(
        id = "RIALTO-S004",
        requirement = "K4.2",
        summary = "remote source differs from its hash in rialto.lock",
    ),
    REMOTE_SCHEME(
        id = "RIALTO-S005",
        requirement = "K4.4",
        summary = "remote source does not use https",
    ),
    MISSING_IDENTITY(
        id = "RIALTO-P001",
        requirement = "D2.2",
        summary = "entity identity is not one of its properties",
    ),
    UNION_VARIANTS(
        id = "RIALTO-P002",
        requirement = "D2.6",
        summary = "union variants are not all entities or all value objects",
    ),
    QUERY_FIELD(
        id = "RIALTO-P003",
        requirement = "Q1.1",
        summary = "query field or operator does not fit the output type",
    ),
    TIEBREAKER(
        id = "RIALTO-P004",
        requirement = "Q1.3",
        summary = "tiebreaker is not a sortable query field",
    ),
    ERROR_CODE(
        id = "RIALTO-P005",
        requirement = "E1.1",
        summary = "operation returns an undeclared error code",
    ),
    COMMAND_PROPERTY(
        id = "RIALTO-P006",
        requirement = "L1.2",
        summary = "command property does not fit its relationship",
    ),
    SOURCE_VERSION(
        id = "RIALTO-P007",
        requirement = "V1.4",
        summary = "source version differs from the document version",
    ),
    UNDECLARED_SCOPE(
        id = "RIALTO-P008",
        requirement = "S1.1",
        summary = "scope used by an operation is not declared",
    ),
    LIST_WIRE(
        id = "RIALTO-P009",
        requirement = "Q1.4",
        summary = "list wire element does not exist in the OpenAPI operation",
    ),
    ERROR_WIRE(
        id = "RIALTO-P010",
        requirement = "E1.3",
        summary = "error wire pointer does not exist in the problem schema",
    ),
    EVENT_SUBJECT(
        id = "RIALTO-P011",
        requirement = "A1.1",
        summary = "event subject is not a property of the event data",
    ),
    DISCRIMINATOR(
        id = "RIALTO-P012",
        requirement = "K2.8",
        summary = "a variant declares the discriminator with a type other than string",
    ),
    LIST_KIND(
        id = "RIALTO-P013",
        requirement = "Q1.6",
        summary =
            "list wire or encodings do not fit a REST or local operation, or a fixed sort takes a " +
                "sort parameter or encoding",
    ),
    SHAPE_CONSTRAINT(
        id = "RIALTO-P014",
        requirement = "D3.5",
        summary = "shape constraint weakens its source property's or does not apply to its type",
    ),
    PARAMETER(
        id = "RIALTO-P015",
        requirement = "R1.5",
        summary = "REST parameter is not mapped, or its mapping does not resolve",
    ),
    CODE_TYPE(
        id = "RIALTO-P016",
        requirement = "E2.2",
        summary = "code types are not one template with {code}, or two codes or categories share a type",
    ),
    DEPRECATION(
        id = "RIALTO-P017",
        requirement = "V2.2",
        summary = "deprecated operation is not marked deprecated in its OpenAPI operation",
    ),
    PATH_LOCATION(
        id = "RIALTO-P018",
        requirement = "E1.5",
        summary = "with locationFormat path, a request body property and a parameter or header share a name",
    ),
    COUNT_FILTER(
        id = "RIALTO-P019",
        requirement = "Q5.2",
        summary = "count operation does not take the list's filter, or requires a parameter the list does not have",
    ),
    CONDITIONAL(
        id = "RIALTO-P020",
        requirement = "R6.1",
        summary = "conditional operation is not a GET or HEAD with If-None-Match, an ETag on success and a 304",
    ),
    BINARY(
        id = "RIALTO-P021",
        requirement = "K2.11",
        summary = "binary without maxSize, in a collection, as a query field, or carried as JSON",
    ),
    API_KEY(
        id = "RIALTO-P022",
        requirement = "S6.1",
        summary = "apiKey scheme of an operation is not declared under auth.apiKeys, or not sent in a header",
    ),
    TRANSITION(
        id = "RIALTO-P023",
        requirement = "L5.1",
        summary =
            "transition state is not a closed enum with a member default, names a non-member or an " +
                "unknown rule, or a command shape contains it",
    ),
    IDEMPOTENCY(
        id = "RIALTO-P024",
        requirement = "L3.2",
        summary = "idempotency on a safe method, or its header is not a header of the OpenAPI operation",
    ),
    MAPPING_COVERAGE(
        id = "RIALTO-P025",
        requirement = "M2.8",
        summary =
            "mapping names a property neither side has, leaves a required property uncovered, or " +
                "uses version: stored without a stored version",
    ),
    STORAGE_COLUMN(
        id = "RIALTO-P026",
        requirement = "P1.5",
        summary =
            "stored property has no or two places, shares a column, or its column type or " +
                "nullability, the type of a join's key column, or the type of an order column, " +
                "does not fit",
    ),
    BATCH_WIRE(
        id = "RIALTO-P027",
        requirement = "L4.5",
        summary =
            "batch wire pointer does not resolve in the OpenAPI operation, or is not an array, or " +
                "index or status is not an integer",
    ),
    IMMUTABLE(
        id = "RIALTO-P028",
        requirement = "L1.6",
        summary = "update command shape has an immutable property",
    ),
    LINK(
        id = "RIALTO-P029",
        requirement = "L6.1",
        summary =
            "link operation does not name a to-many reference with a link table, does not address " +
                "owner and target by identity, or also declares lifecycle, input or transition",
    ),
    UNIQUE_KEY(
        id = "RIALTO-P030",
        requirement = "P3.6",
        summary =
            "unique key names a property without a plain column, or an operation addresses an " +
                "entity by properties that are neither its identity nor a unique key",
    ),
    SOFT_DELETE(
        id = "RIALTO-P031",
        requirement = "P6.3",
        summary =
            "deleted on an operation that does not read or on an entity without soft deletion, a " +
                "delete or restore that names no entity, a restore without restore: true, a subject " +
                "that is not an entity, or a " +
                "command property on the soft-delete marker or by column",
    ),
    REFERENCE_OR_CREATE(
        id = "RIALTO-P032",
        requirement = "L1.7",
        summary =
            "as: [identity, command] outside a create command, on a property that is not a " +
                "reference, without the create command shape of its type, or its mapping lacks a " +
                "from per form",
    ),
    LIST_OUTPUT(
        id = "RIALTO-P033",
        requirement = "R2.7",
        summary =
            "output: list<T> lacks maxItems, its success response is not an array of T, or it is " +
                "also a list operation",
    ),
    ACTOR_SUBJECT(
        id = "RIALTO-P034",
        requirement = "R1.8",
        summary =
            "subject by actor names no entity, maps a parameter to its identity, has an input " +
                "that carries that identity, or belongs to an operation that needs no signed-in " +
                "caller; or an input with role actor belongs to such an operation",
    ),
    SCOPE(
        id = "RIALTO-P035",
        requirement = "P6.11",
        summary =
            "scope names a column twice or a column the table lacks, storage entries on one table " +
                "declare different scopes, a reference lacks a source of the scope of its target, " +
                "a command carries a property on a scope column, or a type with an actor entry is " +
                "reached by an operation that needs no signed-in caller",
    ),
    TRANSITION_FORM(
        id = "RIALTO-P036",
        requirement = "L5.6",
        summary =
            "select names rules of several entities or does not resolve to a string in the request " +
                "body, a sets entry does not fit its property, a create performs a rule that does " +
                "not start from the initial state, or from is declared off a delete with transitions",
    ),
    ACTION_VALUES(
        id = "RIALTO-P037",
        requirement = "U3.9",
        summary =
            "action value pointer does not resolve in the request body, its value does not fit " +
                "the schema there, or it selects no rule",
    ),
    PROPERTY_RULE(
        id = "RIALTO-P038",
        requirement = "D3.6",
        summary =
            "requiredWhen or absentWhen does not name another enum property and its members, or is " +
                "on a non-nullable property; minimumOf or maximumOf does not name another property " +
                "with the same ordered scalar; or uniqueItems is not on a list",
    ),
    PRESENCE(
        id = "RIALTO-P039",
        requirement = "M3.11",
        summary =
            "presence in a toSource mapping, on a target property that is not a non-nullable " +
                "boolean, of a property the source lacks, or from a source that does not keep presence",
    ),
    STORAGE_DERIVED(
        id = "RIALTO-P040",
        requirement = "P2.11",
        summary =
            "join of two steps on a collection, in a command or generated, join without relation " +
                "on a type that is not a scalar or wrapper, or computed count that is not a " +
                "non-nullable integer, is written, or counts no to-many child or link",
    ),
    VIEW_LINK(
        id = "RIALTO-P041",
        requirement = "U5.9",
        summary =
            "menu entry, tab, link or open names a view whose route the data cannot fill, a view " +
                "twice, or a badge that is not the count operation of a list",
    ),
    LIST_VIEW(
        id = "RIALTO-P042",
        requirement = "U6.5",
        summary =
            "row action parameter is filled neither from the route nor the row, a where or column " +
                "does not fit the list operation, or the list operation is neither a list nor list<T>",
    ),
    VIEW_CONDITION(
        id = "RIALTO-P043",
        requirement = "U3.11",
        summary =
            "visibleWhen or enabledWhen path is not an enum property with the listed members, or for " +
                "actor not a string or uuid",
    ),
    FIELD_OPTIONS(
        id = "RIALTO-P044",
        requirement = "U2.11",
        summary =
            "options field is not a to-one reference of its input, or its operation or label does " +
                "not fit the referenced entity",
    ),
    STORAGE_ORDER(
        id = "RIALTO-P045",
        requirement = "P4.13",
        summary =
            "list under children or links declares neither or both of order and orderBy, a set " +
                "declares one, or an orderBy entry is not a sortable property of the element type",
    ),
}
