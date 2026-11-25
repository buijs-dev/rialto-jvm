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
import dev.buijs.rialto.core.model.Keys
import dev.buijs.rialto.core.model.TypeDecl
import dev.buijs.rialto.core.validation.CheckContext
import dev.buijs.rialto.core.validation.ContractTypes

/**
 * Checks that the properties a projection or shape takes exist on its source (K1.2, `RIALTO-K004`). Unlike a
 * name, a property path can only be resolved through the source's own properties, so these use [ContractTypes].
 * A source that does not exist was reported as a reference; its properties are not checked.
 */
internal class PropertyPathChecks(
    private val context: CheckContext,
) {
    private val contract = context.contract
    private val lookup = ContractTypes(contract)

    /** Each path of a projection resolves on its source; the list form is located by index, the map form by name. */
    fun projections() {
        contract.types.forEach { (name, type) ->
            if (type !is TypeDecl.Projection || lookup.propertiesOf(type.source) == null) {
                return@forEach
            }

            val at = Pointer.of(Keys.TYPES, name, Keys.PROPERTIES)
            type.paths.entries.forEachIndexed { index, (property, path) ->
                if (lookup.resolvePath(type.source, path) == null) {
                    context.error(
                        code = Code.UNRESOLVED_REFERENCE,
                        message = "'$path' is not a property of '${type.source}'",
                        pointer = (at / if (type.listed) index else property).text,
                    )
                }
            }
        }
    }

    /** Each property of a shape is a property of the type it shapes. */
    fun shapes() {
        contract.shapes.forEach { (name, shape) ->
            val sourceProperties = lookup.propertiesOf(shape.source) ?: return@forEach
            shape.properties.keys.filter { it !in sourceProperties }.forEach { property ->
                context.error(
                    code = Code.UNRESOLVED_REFERENCE,
                    message = "'$property' is not a property of '${shape.source}'",
                    pointer = Pointer.of(Keys.SHAPES, name, Keys.PROPERTIES, property).text,
                )
            }
        }
    }
}
