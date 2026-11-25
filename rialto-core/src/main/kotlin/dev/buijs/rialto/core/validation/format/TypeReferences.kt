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

import dev.buijs.rialto.core.model.Contract
import dev.buijs.rialto.core.model.Keys
import dev.buijs.rialto.core.model.TypeDecl

/**
 * The references of the `types` section: the variants of a union, the source of a projection, and the events a
 * transition emits. The named types inside type expressions are checked with the expressions themselves
 * ([TypeExpressionChecks]); the paths of a projection by [PropertyPathChecks].
 */
internal fun Contract.typeReferences(): List<Reference> =
    collectReferences {
        types.forEach { (name, type) ->
            val at = Pointer.of(Keys.TYPES, name)
            when (type) {
                is TypeDecl.Union -> {
                    type.variants.forEach { (value, variant) -> type(variant, at / Keys.VARIANTS / value) }
                }
                is TypeDecl.Projection -> {
                    type(type.source, at / Keys.SOURCE)
                }
                is TypeDecl.Entity -> {
                    type.transitions?.rules?.forEach { (rule, declaration) ->
                        each(Kind.EVENT, declaration.emits, at / Keys.TRANSITIONS / Keys.RULES / rule / Keys.EMITS)
                    }
                }
                is TypeDecl.Value, is TypeDecl.Enum -> {
                    // No references of their own.
                }
            }
        }
    }

/**
 * The references of the `shapes` section: the type each shape shapes, and the shape of a property. Whether each
 * property exists on the source is checked by [PropertyPathChecks].
 */
internal fun Contract.shapeReferences(): List<Reference> =
    collectReferences {
        shapes.forEach { (name, shape) ->
            val at = Pointer.of(Keys.SHAPES, name)
            type(shape.source, at / Keys.SOURCE)
            shape.properties.forEach { (property, declaration) ->
                name(Kind.SHAPE, declaration.shape, at / Keys.PROPERTIES / property / Keys.SHAPE)
            }
        }
    }
