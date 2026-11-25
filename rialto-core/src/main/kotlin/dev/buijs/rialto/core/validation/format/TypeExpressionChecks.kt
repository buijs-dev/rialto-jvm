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

import dev.buijs.rialto.core.diagnostics.Code
import dev.buijs.rialto.core.model.Contract
import dev.buijs.rialto.core.model.Keys
import dev.buijs.rialto.core.model.TypeDecl
import dev.buijs.rialto.core.model.TypeExpression
import dev.buijs.rialto.core.validation.CheckContext

/**
 * A type expression in the contract and where it stands. The scalar a wrapper [wraps] is only checked for its
 * form; every other expression for its form, it's named types and the rules of collections.
 */
internal data class TypeUse(
    val text: String,
    val pointer: Pointer,
    val wraps: Boolean = false,
)

/** The type expressions of the `types` section: the scalar each wrapper wraps, and the type of each property. */
internal fun Contract.typeExpressions(): List<TypeUse> =
    buildList {
        types.forEach { (name, type) ->
            val at = Pointer.of(Keys.TYPES, name)
            val properties =
                when (type) {
                    is TypeDecl.Entity -> {
                        type.properties
                    }
                    is TypeDecl.Value -> {
                        type.wraps?.let { add(TypeUse(text = it, pointer = at / Keys.WRAPS, wraps = true)) }
                        type.properties
                    }
                    // A projection lists property paths, not types.
                    is TypeDecl.Projection, is TypeDecl.Union, is TypeDecl.Enum -> {
                        emptyMap()
                    }
                }

            properties.forEach { (property, declaration) ->
                add(TypeUse(text = declaration.type, pointer = at / Keys.PROPERTIES / property / Keys.TYPE))
            }
        }
    }

/** The type expressions of the `views` section: the context of each slot. */
internal fun Contract.slotExpressions(): List<TypeUse> =
    buildList {
        views.forEach { (name, view) ->
            view.slots.forEach { (slot, declaration) ->
                val at = Pointer.of(Keys.VIEWS, name, Keys.SLOTS, slot, Keys.CONTEXT)
                add(TypeUse(text = declaration.context, pointer = at))
            }
        }
    }

/**
 * Checks each type expression: its form (K2.1, `RIALTO-K006`), which the JSON Schema leaves to the tool (K1.7);
 * the named types it uses (K1.2, through [references]); a map key that is a named type must be an enum; and a
 * `binary` is never an element of a collection or a map value (K2.11, `RIALTO-P021`).
 */
internal class TypeExpressionChecks(
    private val context: CheckContext,
    private val references: ReferenceChecks,
) {
    private val types = context.contract.types

    fun check(use: TypeUse) {
        val expression = TypeExpression.parse(use.text)
        if (expression == null) {
            context.error(
                code = Code.TYPE_EXPRESSION,
                message = "Invalid type '${use.text}'",
                pointer = use.pointer.text,
            )
            return
        }

        if (use.wraps) {
            return
        }

        expression.names().forEach { references.check(Reference.Name(Kind.TYPE, it, use.pointer)) }
        mapKey(expression, use.pointer)
        binary(expression, use.pointer)
    }

    private fun mapKey(
        expression: TypeExpression,
        at: Pointer,
    ) {
        val key = ((expression as? TypeExpression.MapOf)?.key as? TypeExpression.Named)?.name ?: return
        val declaration = types[key]
        if (declaration != null && declaration !is TypeDecl.Enum) {
            context.error(
                code = Code.TYPE_EXPRESSION,
                message = "Map key '$key' must be an enum",
                pointer = at.text,
            )
        }
    }

    private fun binary(
        expression: TypeExpression,
        at: Pointer,
    ) {
        val element =
            when (expression) {
                is TypeExpression.ListOf -> expression.element
                is TypeExpression.SetOf -> expression.element
                is TypeExpression.MapOf -> expression.value
                else -> null
            }

        if (element == BINARY) {
            context.error(
                code = Code.BINARY,
                message = "A binary cannot be an element of a collection or a map value",
                pointer = at.text,
            )
        }
    }

    private companion object {
        /** The built-in `binary`, which K2.11 keeps out of collections. */
        val BINARY = TypeExpression.Scalar("binary")
    }
}
