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
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.annotation.JsonValue
import tools.jackson.databind.JsonNode

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
        @get:JsonProperty(Keys.JOIN)
        val steps: List<JoinStep>,

        val key: String,
    ) : ColumnRef()

    companion object {
        @JvmStatic
        @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
        fun of(node: JsonNode): ColumnRef =
            if (node.isString) {
                Plain(node.asString())
            } else if (node.path(Keys.JOIN).isArray) {
                JoinPath(
                    steps =
                        node.path(Keys.JOIN).values().map {
                            JoinStep(
                                column = it.path(Keys.COLUMN).asString(),
                                table = it.path(Keys.TABLE).asString(),
                            )
                        },
                    key = node.path(Keys.KEY).asString(),
                )
            } else {
                Join(
                    join = node.path(Keys.JOIN).asString(),
                    table = node.path(Keys.TABLE).asString(),
                    key = node.path(Keys.KEY).asString(),
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
            None -> Values.NONE
            Physical -> Values.PHYSICAL
            is Soft -> listOfNotNull(Keys.SOFT to soft, by?.let { Keys.BY to it }, Keys.RESTORE to restore).toMap()
        }

    companion object {
        @JvmStatic
        @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
        fun of(node: JsonNode): DeleteMode =
            when {
                node.isString && node.asString() == Values.PHYSICAL -> {
                    Physical
                }
                node.isString -> {
                    None
                }
                else -> {
                    Soft(
                        soft = node.path(Keys.SOFT).asString(),
                        restore = node.path(Keys.RESTORE).asBoolean(false),
                        by = node.path(Keys.BY).takeIf { it.isString }?.asString(),
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
)

data class Retry(
    val attempts: Int,
    val idempotent: Boolean,
)

data class UnionStorage(
    val strategy: String,
    val discriminator: String,
    val variants: Map<String, Map<String, String>> = emptyMap(),
)

/**
 * The storage of one type under `storage`, keyed by its name (persistence profile, P): the [table] in
 * the SQL source, the column of each property, and how children, links, versions, deletion and blobs are kept.
 */
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
