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

package dev.buijs.rialto.core.validation.format

import dev.buijs.rialto.core.model.Blob
import dev.buijs.rialto.core.model.Child
import dev.buijs.rialto.core.model.ColumnRef
import dev.buijs.rialto.core.model.Contract
import dev.buijs.rialto.core.model.DeleteMode
import dev.buijs.rialto.core.model.Keys
import dev.buijs.rialto.core.model.Link
import dev.buijs.rialto.core.model.TypeDecl
import dev.buijs.rialto.core.model.TypeExpression

/**
 * The references of the `storage` section: each storage belongs to a declared type, and its table and every
 * column it names exist in the SQL source.
 */
internal fun Contract.storageReferences(): List<Reference> =
    collectReferences {
        storage.forEach { (name, storage) ->
            val at = Pointer.of(Keys.STORAGE, name)
            val table = storage.table
            type(name, at)
            external(table, at / Keys.TABLE)

            storage.columns.forEach { (property, column) ->
                columnRef(table, column, at / Keys.COLUMNS / property)
            }

            storage.children.forEach { (property, child) ->
                child(table, child, targetTableOf(name, property), at / Keys.CHILDREN / property)
            }

            storage.links.forEach { (property, link) ->
                link(link, targetTableOf(name, property), at / Keys.LINKS / property)
            }

            column(table, storage.version, at / Keys.VERSION)
            softDelete(table, storage.delete, at / Keys.DELETE)
            column(table, storage.storageKey, at / Keys.STORAGE_KEY)

            storage.blobs.forEach { (property, blob) ->
                blob(table, blob, at / Keys.BLOBS / property)
            }
        }
    }

/** A column of [table], directly or through one or more joins. */
private fun ReferenceCollector.columnRef(
    table: String,
    column: ColumnRef,
    at: Pointer,
) {
    when (column) {
        is ColumnRef.Plain -> {
            column(table, column.column, at)
        }
        is ColumnRef.Join -> {
            column(table, column.join, at / Keys.JOIN)
            column(column.table, column.key, at / Keys.KEY)
        }
        is ColumnRef.JoinPath -> {
            // Each step's column is one of the table before it (P2.11).
            var previous = table
            column.steps.forEachIndexed { index, step ->
                column(previous, step.column, at / Keys.JOIN / index / Keys.COLUMN)
                previous = step.table
            }

            column(previous, column.key, at / Keys.KEY)
        }
    }
}

/**
 * An owned child: the key the owner holds is a column of the owner's [table], the position one of the
 * [childTable] (P4.13).
 */
private fun ReferenceCollector.child(
    table: String,
    child: Child,
    childTable: String?,
    at: Pointer,
) {
    column(table, child.ownerKey, at / Keys.OWNER_KEY)
    if (childTable != null) {
        column(childTable, child.order, at / Keys.ORDER)
    }
}

/** A to-many reference: through a link table, or read-only through a foreign key in the [targetTable]. */
private fun ReferenceCollector.link(
    link: Link,
    targetTable: String?,
    at: Pointer,
) {
    val linkTable = link.table
    if (linkTable == null) {
        // Read-only: the foreign key is a column of the target's table (P4.11).
        if (targetTable != null) {
            column(targetTable, link.ownerKey, at / Keys.OWNER_KEY)
        }

        return
    }

    external(linkTable, at / Keys.TABLE)
    column(linkTable, link.ownerKey, at / Keys.OWNER_KEY)
    column(linkTable, link.targetKey, at / Keys.TARGET_KEY)
    // The position column is one of the link table (P4.13).
    column(linkTable, link.order, at / Keys.ORDER)
}

/** The soft-delete marker and the column of who deleted are columns of the table (P6.1, P6.10). */
private fun ReferenceCollector.softDelete(
    table: String,
    delete: DeleteMode?,
    at: Pointer,
) {
    if (delete is DeleteMode.Soft) {
        column(table, delete.soft, at / Keys.SOFT)
        column(table, delete.by, at / Keys.BY)
    }
}

/** The key and metadata of a blob are columns of the table (P7.1). */
private fun ReferenceCollector.blob(
    table: String,
    blob: Blob,
    at: Pointer,
) {
    column(table, blob.key, at / Keys.KEY)
    column(table, blob.mediaType, at / Keys.MEDIA_TYPE)
    column(table, blob.size, at / Keys.SIZE)
    column(table, blob.sha256, at / Keys.SHA256)
}

/** The table of the type [property] of [owner] refers to, when that type is stored. */
private fun Contract.targetTableOf(
    owner: String,
    property: String,
): String? {
    val type = (types[owner] as? TypeDecl.Entity)?.properties?.get(property)?.type ?: return null
    val target = TypeExpression.parse(type)?.names()?.firstOrNull() ?: return null
    return storage[target]?.table
}
