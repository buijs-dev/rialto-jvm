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
 * The checks of the contract format itself, over every section: each reference to a contract element resolves
 * (K1.2, `RIALTO-K004`), and so does each external reference into a source (`RIALTO-S002`); no name is both a type
 * and a shape (K1.3, `RIALTO-K005`); and each type expression is valid (K2.1, `RIALTO-K006`).
 */
internal class ReferenceChecks(
    private val context: CheckContext,
) {
    private val contract = context.contract
    private val typeNames = contract.types.keys
    private val dataNames = contract.types.keys + contract.shapes.keys
    private val operations = contract.operations.keys
    private val events = contract.events.keys
    private val views = contract.views.keys
    private val lookup = ContractTypes(contract)

    fun run() {
        names()
        types()
        shapes()
        operations()
        events()
        mappings()
        storage()
        views()
        navigation()
        auth()
        transitions()
        sources()
    }

    // Helpers -----------------------------------------------------------------------------------

    private fun pointer(vararg segments: Any): String = context.pointer(*segments)

    private fun requireName(
        name: String?,
        known: Set<String>,
        what: String,
        pointer: String,
    ) {
        if (name != null && name !in known) {
            context.error(Code.UNRESOLVED_REFERENCE, "Unknown $what '$name'", pointer)
        }
    }

    /** Checks a type or shape name, or an external `<source>#<location>` reference. */
    private fun requireDataOrExternal(
        reference: String,
        pointer: String,
    ) {
        if ('#' in reference) {
            requireExternal(reference, pointer)
        } else {
            requireName(reference, dataNames, "type or shape", pointer)
        }
    }

    private fun requireExternal(
        reference: String,
        pointer: String,
    ) {
        when (val resolution = context.sources.resolve(reference, contract.sources.keys)) {
            Sources.Resolution.UnknownSource -> {
                context.error(
                    Code.UNRESOLVED_REFERENCE,
                    "Unknown source '${reference.substringBefore('#')}' in '$reference'",
                    pointer,
                )
            }
            is Sources.Resolution.Missing -> {
                context.error(
                    Code.SOURCE_REFERENCE,
                    "'${reference.substringAfter(
                        '#',
                    )}' does not exist in ${resolution.source.file.fileName}",
                    pointer,
                )
            }
            else -> {
                Unit
            }
        }
    }

    // K1.3 --------------------------------------------------------------------------------------

    private fun names() {
        contract.shapes.keys.filter { it in typeNames }.forEach {
            context.error(
                Code.DUPLICATE_NAME,
                "'$it' is both a type and a shape",
                pointer("shapes", it),
            )
        }
    }

    // Types and shapes --------------------------------------------------------------------------

    private fun types() {
        contract.types.forEach { (name, type) ->
            when (type) {
                is TypeDecl.Entity -> {
                    properties(name, type.properties)
                }
                is TypeDecl.Value -> {
                    properties(name, type.properties)
                }
                is TypeDecl.Union -> {
                    type.variants.forEach { (value, variant) ->
                        requireName(
                            variant,
                            typeNames,
                            "type",
                            pointer("types", name, "variants", value),
                        )
                    }
                }
                is TypeDecl.Projection -> {
                    projection(name, type)
                }
                is TypeDecl.Enum -> {
                    Unit
                }
            }
        }
    }

    private fun properties(
        owner: String,
        properties: Map<String, Property>,
    ) {
        properties.forEach { (property, declaration) ->
            val at = pointer("types", owner, "properties", property, "type")
            val expression = TypeExpression.parse(declaration.type)
            if (expression == null) {
                context.error(Code.TYPE_EXPRESSION, "Invalid type '${declaration.type}'", at)
                return@forEach
            }

            expression.names().forEach { requireName(it, typeNames, "type", at) }

            if (expression is TypeExpression.MapOf && expression.key is TypeExpression.Named) {
                val key = (expression.key as TypeExpression.Named).name
                if (contract.types[key] != null && contract.types[key] !is TypeDecl.Enum) {
                    context.error(Code.TYPE_EXPRESSION, "Map key '$key' must be an enum", at)
                }
            }
        }
    }

    private fun projection(
        name: String,
        type: TypeDecl.Projection,
    ) {
        requireName(type.source, typeNames, "type", pointer("types", name, "source"))
        if (lookup.propertiesOf(type.source) == null) return
        type.paths.entries.forEachIndexed { index, (property, path) ->
            if (lookup.resolvePath(type.source, path) == null) {
                // The list form is located by index, the map form by name.
                val at = if (type.listed) index else property
                context.error(
                    Code.UNRESOLVED_REFERENCE,
                    "'$path' is not a property of '${type.source}'",
                    pointer("types", name, "properties", at),
                )
            }
        }
    }

    private fun shapes() {
        contract.shapes.forEach { (name, shape) ->
            requireName(shape.source, typeNames, "type", pointer("shapes", name, "source"))
            val sourceProperties = lookup.propertiesOf(shape.source) ?: return@forEach
            shape.properties.forEach { (property, declaration) ->
                val at = pointer("shapes", name, "properties", property)
                if (sourceProperties[property] == null) {
                    context.error(
                        Code.UNRESOLVED_REFERENCE,
                        "'$property' is not a property of '${shape.source}'",
                        at,
                    )
                    return@forEach
                }

                requireName(declaration.shape, contract.shapes.keys, "shape", "$at/shape")
            }
        }
    }

    // Operations and events ---------------------------------------------------------------------

    private fun operations() {
        contract.operations.forEach { (name, operation) ->
            val at = pointer("operations", name)
            operation.rest?.all?.forEach { requireExternal(it, "$at/rest") }

            requireName(operation.input, dataNames, "type or shape", "$at/input")
            requireName(operation.outputType, dataNames, "type or shape", "$at/output")
            val subjectAt = if (operation.subject?.by == null) "$at/subject" else "$at/subject/type"
            requireName(operation.subject?.type, typeNames, "type", subjectAt)
            requireName(
                operation.deprecated?.successor,
                operations,
                "operation",
                "$at/deprecated/successor",
            )
            operation.emits.forEachIndexed { index, event ->
                requireName(event, events, "event", "$at/emits/$index")
            }

            requireName(
                operation.list?.count?.operation,
                operations,
                "operation",
                "$at/list/count/operation",
            )
        }
    }

    private fun events() {
        contract.events.forEach { (name, event) ->
            val at = pointer("events", name)
            requireName(event.data, dataNames, "type or shape", "$at/data")
            requireExternal(event.channel, "$at/channel")
        }

        contract.streams.forEach { (name, stream) ->
            val at = pointer("streams", name)
            stream.events.forEachIndexed { index, event ->
                requireName(event, events, "event", "$at/events/$index")
            }

            requireExternal(stream.channel, "$at/channel")
        }

        contract.subscriptions.forEach { (name, subscription) ->
            val at = pointer("subscriptions", name)
            requireName(subscription.event, events, "event", "$at/event")
            requireExternal(subscription.delivery.deadLetter, "$at/delivery/deadLetter")
        }
    }

    // Mapping and storage -----------------------------------------------------------------------

    private fun mappings() {
        contract.mappings.forEachIndexed { index, mapping ->
            requireDataOrExternal(mapping.source, pointer("mappings", index, "source"))
            requireDataOrExternal(mapping.target, pointer("mappings", index, "target"))
        }
    }

    private fun storage() {
        contract.storage.forEach { (name, storage) ->
            val at = pointer("storage", name)
            requireName(name, typeNames, "type", at)
            requireExternal(storage.table, "$at/table")
            val table = storage.table
            storage.columns.forEach { (property, column) ->
                column(table, column, "$at/columns/${LocatedDocument.segment(property)}")
            }

            storage.children.forEach { (property, child) ->
                val childAt = "$at/children/${LocatedDocument.segment(property)}"
                child.ownerKey?.let { requireExternal("$table.$it", "$childAt/ownerKey") }

                // The position column is one of the child's table (P4.13).
                child.order?.let { order ->
                    targetTableOf(
                        name,
                        property,
                    )?.let { requireExternal("$it.$order", "$childAt/order") }
                }
            }

            storage.links.forEach { (property, link) ->
                val linkAt = "$at/links/${LocatedDocument.segment(property)}"
                if (link.table == null) {
                    // Read-only: the foreign key is a column of the target's table (P4.11).
                    targetTableOf(
                        name,
                        property,
                    )?.let { requireExternal("$it.${link.ownerKey}", "$linkAt/ownerKey") }
                } else {
                    requireExternal(link.table, "$linkAt/table")
                    requireExternal("${link.table}.${link.ownerKey}", "$linkAt/ownerKey")
                    requireExternal("${link.table}.${link.targetKey}", "$linkAt/targetKey")
                    // The position column is one of the link table (P4.13).
                    link.order?.let { requireExternal("${link.table}.$it", "$linkAt/order") }
                }
            }

            storage.version?.let { requireExternal("$table.$it", "$at/version") }

            // The soft-delete marker and the column of who deleted (P6.1, P6.10).
            (storage.delete as? DeleteMode.Soft)?.let { delete ->
                requireExternal("$table.${delete.soft}", "$at/delete/soft")
                delete.by?.let { requireExternal("$table.$it", "$at/delete/by") }
            }

            storage.storageKey?.let { requireExternal("$table.$it", "$at/storageKey") }

            // The key and metadata of a blob are columns of the table (P7.1).
            storage.blobs.forEach { (property, blob) ->
                val blobAt = "$at/blobs/${LocatedDocument.segment(property)}"
                listOf(
                    "key" to blob.key,
                    "mediaType" to blob.mediaType,
                    "size" to blob.size,
                    "sha256" to blob.sha256,
                ).forEach { (key, column) ->
                    if (column != null) requireExternal("$table.$column", "$blobAt/$key")
                }
            }
        }
    }

    private fun column(
        table: String,
        column: ColumnRef,
        at: String,
    ) {
        when (column) {
            is ColumnRef.Plain -> {
                requireExternal("$table.${column.column}", at)
            }
            is ColumnRef.Join -> {
                requireExternal("$table.${column.join}", "$at/join")
                requireExternal("${column.table}.${column.key}", "$at/key")
            }
            is ColumnRef.JoinPath -> {
                // Each step's column is one of the table before it (P2.11).
                var previous = table
                column.steps.forEachIndexed { index, step ->
                    requireExternal("$previous.${step.column}", "$at/join/$index/column")
                    previous = step.table
                }

                requireExternal("$previous.${column.key}", "$at/key")
            }
        }
    }

    /** The table of the type [property] of [owner] refers to, when it is stored. */
    private fun targetTableOf(
        owner: String,
        property: String,
    ): String? {
        val type =
            (contract.types[owner] as? TypeDecl.Entity)?.properties?.get(property)?.type
                ?: return null
        val target = TypeExpression.parse(type)?.names()?.firstOrNull() ?: return null
        return contract.storage[target]?.table
    }

    // Presentation ------------------------------------------------------------------------------

    private fun views() {
        contract.views.forEach { (name, view) ->
            val at = pointer("views", name)
            requireName(view.load, operations, "operation", "$at/load")
            view.fields.forEach { (field, declaration) ->
                val fieldAt = "$at/fields/${LocatedDocument.segment(field)}"
                requireName(declaration.edit, operations, "operation", "$fieldAt/edit")
                requireName(declaration.link, views, "view", "$fieldAt/link")
                requireName(
                    declaration.options?.operation,
                    operations,
                    "operation",
                    "$fieldAt/options/operation",
                )
            }

            view.actions.forEach { (action, declaration) ->
                requireName(
                    declaration.operation,
                    operations,
                    "operation",
                    "$at/actions/$action/operation",
                )
            }

            view.tabs.forEachIndexed { index, tab ->
                requireName(
                    tab,
                    views,
                    "view",
                    "$at/tabs/$index",
                )
            }

            requireName(view.list?.operation, operations, "operation", "$at/list/operation")
            requireName(view.list?.open, views, "view", "$at/list/open")
            view.list?.actions?.forEach { (action, declaration) ->
                requireName(
                    declaration.operation,
                    operations,
                    "operation",
                    "$at/list/actions/$action/operation",
                )
            }

            view.refreshOn.forEachIndexed { index, event ->
                requireName(event, events, "event", "$at/refreshOn/$index")
            }
        }
    }

    private fun navigation() {
        val navigation = contract.navigation ?: return
        requireName(navigation.home, views, "view", "/navigation/home")
        navigation.menus.forEach { (menu, entries) ->
            entries.forEachIndexed { index, entry ->
                requireName(entry.view, views, "view", "/navigation/$menu/$index/view")
                requireName(entry.badge, operations, "operation", "/navigation/$menu/$index/badge")
            }
        }
    }

    // Auth, lifecycle and sources ---------------------------------------------------------------

    private fun auth() {
        val auth = contract.auth ?: return
        auth.clients.forEach { (name, client) ->
            requireName(
                client.provider,
                auth.providers.keys,
                "provider",
                pointer("auth", "clients", name, "provider"),
            )
        }

        auth.scopes.forEach { (scope, declaration) ->
            requireExternal(declaration.scheme, pointer("auth", "scopes", scope, "scheme"))
        }

        auth.apiKeys.forEach { (key, declaration) ->
            requireExternal(declaration.scheme, pointer("auth", "apiKeys", key, "scheme"))
        }
    }

    private fun transitions() {
        contract.types.forEach { (name, type) ->
            val transitions = (type as? TypeDecl.Entity)?.transitions ?: return@forEach
            transitions.rules.forEach { (rule, declaration) ->
                val at = pointer("types", name, "transitions", "rules", rule)
                declaration.emits.forEachIndexed { index, event ->
                    requireName(event, events, "event", "$at/emits/$index")
                }
            }
        }
    }

    private fun sources() {
        contract.sources.forEach { (name, source) ->
            requireName(
                source.successor,
                contract.sources.keys,
                "source",
                pointer("sources", name, "successor"),
            )
        }
    }
}
