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

import dev.buijs.rialto.core.model.Keys
import dev.buijs.rialto.core.utilities.JsonPointer

/**
 * A JSON Pointer that is built one segment at a time: `Pointer.of(Keys.STORAGE, name) / Keys.TABLE`. Each segment
 * is escaped as it is added.
 */
@JvmInline
internal value class Pointer(
    val text: String,
) {
    operator fun div(segment: Any): Pointer = Pointer(text + JsonPointer.of(segment))

    companion object {
        fun of(vararg segments: Any): Pointer = Pointer(JsonPointer.of(*segments))
    }
}

/** The kinds of contract element a name can refer to; [label] names the kind in a finding. */
internal enum class Kind(
    val label: String,
) {
    TYPE(Keys.TYPE),
    DATA("${Keys.TYPE} or ${Keys.SHAPE}"),
    SHAPE(Keys.SHAPE),
    OPERATION(Keys.OPERATION),
    EVENT(Keys.EVENT),
    VIEW(Keys.VIEW),
    PROVIDER(Keys.PROVIDER),
    SOURCE(Keys.SOURCE),
}

/**
 * One place in the contract that refers to something else: a name of a contract element, or a
 * `<source>#<location>` in a loaded source. Finding the references of a section only reads the model;
 * [ReferenceChecks] decides whether each one resolves.
 */
internal sealed interface Reference {
    val pointer: Pointer

    data class Name(
        val kind: Kind,
        val name: String,
        override val pointer: Pointer,
    ) : Reference

    data class External(
        val reference: String,
        override val pointer: Pointer,
        /** Whether it must name an OpenAPI operation, `/paths/<path>/<method>` (a REST binding). */
        val operation: Boolean = false,
    ) : Reference
}

/** Collects the references of a section in the order they are added; a name or reference that is not set is skipped. */
internal class ReferenceCollector {
    val references: List<Reference>
        field = mutableListOf<Reference>()

    fun name(
        kind: Kind,
        name: String?,
        at: Pointer,
    ) {
        if (name != null) {
            references += Reference.Name(kind, name, at)
        }
    }

    fun type(
        name: String?,
        at: Pointer,
    ) = name(Kind.TYPE, name, at)

    /** A type or a shape. */
    fun data(
        name: String?,
        at: Pointer,
    ) = name(Kind.DATA, name, at)

    fun operation(
        name: String?,
        at: Pointer,
    ) = name(Kind.OPERATION, name, at)

    fun event(
        name: String?,
        at: Pointer,
    ) = name(Kind.EVENT, name, at)

    fun view(
        name: String?,
        at: Pointer,
    ) = name(Kind.VIEW, name, at)

    /** Each name of [names], located by its index. */
    fun each(
        kind: Kind,
        names: List<String>,
        at: Pointer,
    ) = names.forEachIndexed { index, name -> name(kind, name, at / index) }

    /** A type or shape name, or an external `<source>#<location>` reference. */
    fun dataOrExternal(
        reference: String,
        at: Pointer,
    ) = if ('#' in reference) external(reference, at) else data(reference, at)

    fun external(
        reference: String?,
        at: Pointer,
    ) {
        if (reference != null) {
            references += Reference.External(reference, at)
        }
    }

    /** An OpenAPI operation, `<source>#/paths/<path>/<method>`. */
    fun restOperation(
        reference: String,
        at: Pointer,
    ) {
        references += Reference.External(reference, at, operation = true)
    }

    /** The column [column] of [table], `<source>#<table>`, when it is set. */
    fun column(
        table: String,
        column: String?,
        at: Pointer,
    ) = external(column?.let { "$table.$it" }, at)
}

/** The references [block] adds, in order. */
internal fun collectReferences(block: ReferenceCollector.() -> Unit): List<Reference> =
    ReferenceCollector().apply(block).references
