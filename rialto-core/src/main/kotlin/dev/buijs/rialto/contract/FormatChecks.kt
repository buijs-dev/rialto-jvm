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
 *
 * The JSON Schema only checks the structure of each element on its own; it cannot see that a name used in one
 * place is declared in another. These checks close that gap for slice 0, so a valid contract is a connected
 * whole: every name it uses exists, and every `<source>#<location>` lands in a loaded source. The profile checks
 * of later slices build on that and can look names up without checking them again.
 */
internal object FormatChecks : ContractChecks {
    override fun run(context: CheckContext) = FormatRun(context).run()
}

/**
 * One run of [FormatChecks] over one contract: holds the name sets of that contract, so each section's check
 * reads like the list of references it has. [run] walks the sections in the order of the document.
 */
private class FormatRun(
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

    /** Reports `RIALTO-K004` when [name] is set but not among the [known] names; [what] names the kind of element. */
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

    private fun requireType(
        name: String?,
        pointer: String,
    ) = requireName(name, typeNames, "type", pointer)

    private fun requireTypeOrShape(
        name: String?,
        pointer: String,
    ) = requireName(name, dataNames, "type or shape", pointer)

    private fun requireOperation(
        name: String?,
        pointer: String,
    ) = requireName(name, operations, "operation", pointer)

    private fun requireEvent(
        name: String?,
        pointer: String,
    ) = requireName(name, events, "event", pointer)

    private fun requireView(
        name: String?,
        pointer: String,
    ) = requireName(name, views, "view", pointer)

    /** Checks a type or shape name, or an external `<source>#<location>` reference. */
    private fun requireDataOrExternal(
        reference: String,
        pointer: String,
    ) {
        if ('#' in reference) {
            requireExternal(reference, pointer)
        } else {
            requireTypeOrShape(reference, pointer)
        }
    }

    /**
     * Checks a `<source>#<location>` reference: an undeclared source is `RIALTO-K004`, a location the source lacks
     * `RIALTO-S002`. A declared source that could not be loaded was reported when loading, so it is not reported
     * again here.
     */
    private fun requireExternal(
        reference: String,
        pointer: String,
    ) {
        val ref = ExternalRef.of(reference)
        when (val resolution = context.sources.resolve(reference, contract.sources.keys)) {
            Sources.Resolution.UnknownSource -> {
                context.error(
                    Code.UNRESOLVED_REFERENCE,
                    "Unknown source '${ref.source}' in '$reference'",
                    pointer,
                )
            }
            is Sources.Resolution.Missing -> {
                context.error(
                    Code.SOURCE_REFERENCE,
                    "'${ref.location}' does not exist in ${resolution.source.file.fileName}",
                    pointer,
                )
            }
            // Resolved: nothing to report. Unloaded: reported when the source was loaded.
            Sources.Resolution.Resolved, Sources.Resolution.Unloaded -> {
            }
        }
    }

    /** Checks the column [column] of [table], `<source>#<table>`, when it is set. */
    private fun requireColumn(
        table: String,
        column: String?,
        pointer: String,
    ) {
        if (column != null) {
            requireExternal("$table.$column", pointer)
        }
    }

    // K1.3 --------------------------------------------------------------------------------------

    private fun names() {
        contract.shapes.keys.filter { it in typeNames }.forEach {
            context.error(
                Code.DUPLICATE_NAME,
                "'$it' is both a type and a shape",
                pointer(Keys.SHAPES, it),
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
                    wraps(name, type)
                    properties(name, type.properties)
                }
                is TypeDecl.Union -> {
                    type.variants.forEach { (value, variant) ->
                        requireType(variant, pointer(Keys.TYPES, name, Keys.VARIANTS, value))
                    }
                }
                is TypeDecl.Projection -> {
                    projection(name, type)
                }
                is TypeDecl.Enum -> {
                }
            }
        }
    }

    /** The scalar a wrapper wraps is a type expression too: `decimal(2,3)` is as invalid here as on a property. */
    private fun wraps(
        owner: String,
        type: TypeDecl.Value,
    ) {
        val wraps = type.wraps ?: return
        if (TypeExpression.parse(wraps) == null) {
            context.error(
                Code.TYPE_EXPRESSION,
                "Invalid type '$wraps'",
                pointer(Keys.TYPES, owner, Keys.WRAPS),
            )
        }
    }

    private fun properties(
        owner: String,
        properties: Map<String, Property>,
    ) {
        properties.forEach { (property, declaration) ->
            val at = pointer(Keys.TYPES, owner, Keys.PROPERTIES, property, Keys.TYPE)
            val expression = TypeExpression.parse(declaration.type)
            if (expression == null) {
                context.error(Code.TYPE_EXPRESSION, "Invalid type '${declaration.type}'", at)
                return@forEach
            }

            expression.names().forEach { requireType(it, at) }

            if (expression is TypeExpression.MapOf && expression.key is TypeExpression.Named) {
                val key = expression.key.name
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
        requireType(type.source, pointer(Keys.TYPES, name, Keys.SOURCE))
        if (lookup.propertiesOf(type.source) == null) {
            return
        }

        type.paths.entries.forEachIndexed { index, (property, path) ->
            if (lookup.resolvePath(type.source, path) == null) {
                // The list form is located by index, the map form by name.
                val at = if (type.listed) index else property
                context.error(
                    Code.UNRESOLVED_REFERENCE,
                    "'$path' is not a property of '${type.source}'",
                    pointer(Keys.TYPES, name, Keys.PROPERTIES, at),
                )
            }
        }
    }

    private fun shapes() {
        contract.shapes.forEach { (name, shape) ->
            requireType(shape.source, pointer(Keys.SHAPES, name, Keys.SOURCE))
            val sourceProperties = lookup.propertiesOf(shape.source) ?: return@forEach
            shape.properties.forEach { (property, declaration) ->
                val at = pointer(Keys.SHAPES, name, Keys.PROPERTIES, property)
                if (sourceProperties[property] == null) {
                    context.error(
                        Code.UNRESOLVED_REFERENCE,
                        "'$property' is not a property of '${shape.source}'",
                        at,
                    )
                    return@forEach
                }

                requireName(declaration.shape, contract.shapes.keys, "shape", at + pointer(Keys.SHAPE))
            }
        }
    }

    // Operations and events ---------------------------------------------------------------------

    private fun operations() {
        contract.operations.forEach { (name, operation) ->
            val at = pointer(Keys.OPERATIONS, name)
            operation.rest?.all?.forEach { requireExternal(it, at + pointer(Keys.REST)) }

            requireTypeOrShape(operation.input, at + pointer(Keys.INPUT))
            requireTypeOrShape(operation.outputType, at + pointer(Keys.OUTPUT))
            val subjectAt =
                if (operation.subject?.by == null) {
                    at + pointer(Keys.SUBJECT)
                } else {
                    at + pointer(Keys.SUBJECT, Keys.TYPE)
                }

            requireType(operation.subject?.type, subjectAt)
            requireOperation(operation.deprecated?.successor, at + pointer(Keys.DEPRECATED, Keys.SUCCESSOR))
            operation.emits.forEachIndexed { index, event ->
                requireEvent(event, at + pointer(Keys.EMITS, index))
            }

            requireOperation(operation.list?.count?.operation, at + pointer(Keys.LIST, Keys.COUNT, Keys.OPERATION))
        }
    }

    private fun events() {
        contract.events.forEach { (name, event) ->
            val at = pointer(Keys.EVENTS, name)
            requireTypeOrShape(event.data, at + pointer(Keys.DATA))
            requireExternal(event.channel, at + pointer(Keys.CHANNEL))
        }

        contract.streams.forEach { (name, stream) ->
            val at = pointer(Keys.STREAMS, name)
            stream.events.forEachIndexed { index, event ->
                requireEvent(event, at + pointer(Keys.EVENTS, index))
            }

            requireExternal(stream.channel, at + pointer(Keys.CHANNEL))
        }

        contract.subscriptions.forEach { (name, subscription) ->
            val at = pointer(Keys.SUBSCRIPTIONS, name)
            requireEvent(subscription.event, at + pointer(Keys.EVENT))
            requireExternal(subscription.delivery.deadLetter, at + pointer(Keys.DELIVERY, Keys.DEAD_LETTER))
        }
    }

    // Mapping and storage -----------------------------------------------------------------------

    private fun mappings() {
        contract.mappings.forEachIndexed { index, mapping ->
            requireDataOrExternal(mapping.source, pointer(Keys.MAPPINGS, index, Keys.SOURCE))
            requireDataOrExternal(mapping.target, pointer(Keys.MAPPINGS, index, Keys.TARGET))
        }
    }

    private fun storage() {
        contract.storage.forEach { (name, storage) ->
            val at = pointer(Keys.STORAGE, name)
            val table = storage.table
            requireType(name, at)
            requireExternal(table, at + pointer(Keys.TABLE))

            storage.columns.forEach { (property, column) ->
                column(table, column, at + pointer(Keys.COLUMNS, property))
            }

            storage.children.forEach { (property, child) ->
                child(name, property, child, table, at + pointer(Keys.CHILDREN, property))
            }

            storage.links.forEach { (property, link) ->
                link(name, property, link, at + pointer(Keys.LINKS, property))
            }

            requireColumn(table, storage.version, at + pointer(Keys.VERSION))
            softDelete(table, storage.delete, at + pointer(Keys.DELETE))
            requireColumn(table, storage.storageKey, at + pointer(Keys.STORAGE_KEY))

            storage.blobs.forEach { (property, blob) ->
                blob(table, blob, at + pointer(Keys.BLOBS, property))
            }
        }
    }

    /** An owned child: the key the owner holds is a column of the owner's table, the position one of the child's. */
    private fun child(
        owner: String,
        property: String,
        child: Child,
        table: String,
        at: String,
    ) {
        requireColumn(table, child.ownerKey, at + pointer(Keys.OWNER_KEY))

        // The position column is one of the child's table (P4.13).
        val childTable = targetTableOf(owner, property)
        if (childTable != null) {
            requireColumn(childTable, child.order, at + pointer(Keys.ORDER))
        }
    }

    /** A to-many reference: through a link table, or read-only through a foreign key in the target's table. */
    private fun link(
        owner: String,
        property: String,
        link: Link,
        at: String,
    ) {
        val linkTable = link.table
        if (linkTable == null) {
            // Read-only: the foreign key is a column of the target's table (P4.11).
            val targetTable = targetTableOf(owner, property)
            if (targetTable != null) {
                requireColumn(targetTable, link.ownerKey, at + pointer(Keys.OWNER_KEY))
            }

            return
        }

        requireExternal(linkTable, at + pointer(Keys.TABLE))
        requireColumn(linkTable, link.ownerKey, at + pointer(Keys.OWNER_KEY))
        requireColumn(linkTable, link.targetKey, at + pointer(Keys.TARGET_KEY))
        // The position column is one of the link table (P4.13).
        requireColumn(linkTable, link.order, at + pointer(Keys.ORDER))
    }

    /** The soft-delete marker and the column of who deleted are columns of the table (P6.1, P6.10). */
    private fun softDelete(
        table: String,
        delete: DeleteMode?,
        at: String,
    ) {
        if (delete !is DeleteMode.Soft) {
            return
        }

        requireColumn(table, delete.soft, at + pointer(Keys.SOFT))
        requireColumn(table, delete.by, at + pointer(Keys.BY))
    }

    /** The key and metadata of a blob are columns of the table (P7.1). */
    private fun blob(
        table: String,
        blob: Blob,
        at: String,
    ) {
        requireColumn(table, blob.key, at + pointer(Keys.KEY))
        requireColumn(table, blob.mediaType, at + pointer(Keys.MEDIA_TYPE))
        requireColumn(table, blob.size, at + pointer(Keys.SIZE))
        requireColumn(table, blob.sha256, at + pointer(Keys.SHA256))
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
                requireExternal("$table.${column.join}", at + pointer(Keys.JOIN))
                requireExternal("${column.table}.${column.key}", at + pointer(Keys.KEY))
            }
            is ColumnRef.JoinPath -> {
                // Each step's column is one of the table before it (P2.11).
                var previous = table
                column.steps.forEachIndexed { index, step ->
                    requireExternal("$previous.${step.column}", at + pointer(Keys.JOIN, index, Keys.COLUMN))
                    previous = step.table
                }

                requireExternal("$previous.${column.key}", at + pointer(Keys.KEY))
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
            val at = pointer(Keys.VIEWS, name)
            requireOperation(view.load, at + pointer(Keys.LOAD))
            requireOperation(view.submit, at + pointer(Keys.SUBMIT))
            view.fields.forEach { (field, declaration) ->
                val fieldAt = at + pointer(Keys.FIELDS, field)
                requireOperation(declaration.edit, fieldAt + pointer(Keys.EDIT))
                requireView(declaration.link, fieldAt + pointer(Keys.LINK))
                requireOperation(declaration.options?.operation, fieldAt + pointer(Keys.OPTIONS, Keys.OPERATION))
            }

            view.actions.forEach { (action, declaration) ->
                requireOperation(
                    declaration.operation,
                    at + pointer(Keys.ACTIONS, action, Keys.OPERATION),
                )
            }

            view.tabs.forEachIndexed { index, tab ->
                requireView(tab, at + pointer(Keys.TABS, index))
            }

            requireOperation(view.list?.operation, at + pointer(Keys.LIST, Keys.OPERATION))
            requireView(view.list?.open, at + pointer(Keys.LIST, Keys.OPEN))
            view.list?.actions?.forEach { (action, declaration) ->
                requireOperation(
                    declaration.operation,
                    at + pointer(Keys.LIST, Keys.ACTIONS, action, Keys.OPERATION),
                )
            }

            view.refreshOn.forEachIndexed { index, event ->
                requireEvent(event, at + pointer(Keys.REFRESH_ON, index))
            }
        }
    }

    private fun navigation() {
        val navigation = contract.navigation ?: return
        requireView(navigation.home, pointer(Keys.NAVIGATION, Keys.HOME))
        navigation.menus.forEach { (menu, entries) ->
            entries.forEachIndexed { index, entry ->
                requireView(entry.view, pointer(Keys.NAVIGATION, menu, index, Keys.VIEW))
                requireOperation(entry.badge, pointer(Keys.NAVIGATION, menu, index, Keys.BADGE))
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
                pointer(Keys.AUTH, Keys.CLIENTS, name, Keys.PROVIDER),
            )
        }

        auth.scopes.forEach { (scope, declaration) ->
            requireExternal(declaration.scheme, pointer(Keys.AUTH, Keys.SCOPES, scope, Keys.SCHEME))
        }

        auth.apiKeys.forEach { (key, declaration) ->
            requireExternal(declaration.scheme, pointer(Keys.AUTH, Keys.API_KEYS, key, Keys.SCHEME))
        }
    }

    private fun transitions() {
        contract.types.forEach { (name, type) ->
            val transitions = (type as? TypeDecl.Entity)?.transitions ?: return@forEach
            transitions.rules.forEach { (rule, declaration) ->
                val at = pointer(Keys.TYPES, name, Keys.TRANSITIONS, Keys.RULES, rule)
                declaration.emits.forEachIndexed { index, event ->
                    requireEvent(event, at + pointer(Keys.EMITS, index))
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
                pointer(Keys.SOURCES, name, Keys.SUCCESSOR),
            )
        }
    }
}
