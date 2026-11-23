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
 * The keys of a contract document, as the JSON Schema of the specification names them: one constant per spelling.
 *
 * Most keys never appear in code, because the model binds them by the name of its properties. These are the ones
 * code does spell out: where it reads or writes the document as a tree, where a property is named differently
 * from its key, and where a check builds the JSON Pointer of a finding. A key that is misspelled there is not a
 * compile error: the read finds nothing and the finding lands on the wrong line. Keeping them in one place, and
 * testing each against the schema, turns that into a failing test.
 *
 * A key has one constant however often the format uses the word: [TYPE] is the type of property, of an event
 * and of a subject. Values the contract can hold, such as `entity` or `physical`, are not keys; they live in
 * [Values].
 */
internal object Keys {
    // Top-level sections ------------------------------------------------------------------------
    const val AUTH: String = "auth"
    const val EVENTS: String = "events"
    const val INCLUDE: String = "include"
    const val INFO: String = "info"
    const val MAPPINGS: String = "mappings"
    const val NAVIGATION: String = "navigation"
    const val OPERATIONS: String = "operations"
    const val RIALTO: String = "rialto"
    const val SHAPES: String = "shapes"
    const val SOURCES: String = "sources"
    const val STORAGE: String = "storage"
    const val STREAMS: String = "streams"
    const val SUBSCRIPTIONS: String = "subscriptions"
    const val TYPES: String = "types"
    const val VIEWS: String = "views"

    // Keys within a section ---------------------------------------------------------------------
    const val ACTIONS: String = "actions"
    const val API_KEYS: String = "apiKeys"
    const val AS: String = "as"
    const val BADGE: String = "badge"
    const val BLOBS: String = "blobs"
    const val BY: String = "by"
    const val CHANNEL: String = "channel"
    const val CHILDREN: String = "children"
    const val CLIENTS: String = "clients"
    const val COLUMN: String = "column"
    const val COLUMNS: String = "columns"
    const val COMMAND: String = "command"
    const val COUNT: String = "count"
    const val DATA: String = "data"
    const val DEAD_LETTER: String = "deadLetter"
    const val DELETE: String = "delete"
    const val DELIVERY: String = "delivery"
    const val DEPRECATED: String = "deprecated"
    const val DESCRIPTION: String = "description"
    const val DIRECTION: String = "direction"
    const val EDIT: String = "edit"
    const val EMITS: String = "emits"
    const val EVENT: String = "event"
    const val FIELD: String = "field"
    const val FIELDS: String = "fields"
    const val HOME: String = "home"
    const val IDENTITY: String = "identity"
    const val INPUT: String = "input"
    const val JOIN: String = "join"
    const val KEY: String = "key"
    const val KIND: String = "kind"
    const val LINK: String = "link"
    const val LINKS: String = "links"
    const val LIST: String = "list"
    const val LOAD: String = "load"
    const val MEDIA_TYPE: String = "mediaType"
    const val OPEN: String = "open"
    const val OPERATION: String = "operation"
    const val OPTIONS: String = "options"
    const val ORDER: String = "order"
    const val OUTPUT: String = "output"
    const val OWNER_KEY: String = "ownerKey"
    const val PATH: String = "path"
    const val PRIMARY: String = "primary"
    const val PROPERTIES: String = "properties"
    const val PROVIDER: String = "provider"
    const val REFRESH_ON: String = "refreshOn"
    const val REST: String = "rest"
    const val RESTORE: String = "restore"
    const val RULES: String = "rules"
    const val SCHEME: String = "scheme"
    const val SCOPES: String = "scopes"
    const val SECONDARY: String = "secondary"
    const val SELECT: String = "select"
    const val SHA256: String = "sha256"
    const val SHAPE: String = "shape"
    const val SIZE: String = "size"
    const val SOFT: String = "soft"
    const val SOURCE: String = "source"
    const val STORAGE_KEY: String = "storageKey"
    const val SUBJECT: String = "subject"
    const val SUBMIT: String = "submit"
    const val SUCCESSOR: String = "successor"
    const val TABLE: String = "table"
    const val TABS: String = "tabs"
    const val TARGET: String = "target"
    const val TARGET_KEY: String = "targetKey"
    const val TRANSITION: String = "transition"
    const val TRANSITIONS: String = "transitions"
    const val TYPE: String = "type"
    const val VARIANTS: String = "variants"
    const val VERSION: String = "version"
    const val VIEW: String = "view"
    const val WRAPS: String = "wraps"
}
