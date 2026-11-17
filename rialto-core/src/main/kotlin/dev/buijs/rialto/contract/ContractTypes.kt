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

/** Looks up the properties of types and shapes, and resolves property paths. */
internal class ContractTypes(
    private val contract: Contract,
) {
    /** The properties of a type or shape, following a projection to its source. */
    fun propertiesOf(
        name: String,
        depth: Int = 0,
    ): Map<String, Property>? {
        // A projection or shape whose sources go round in a circle has no properties.
        if (depth > MAX_DEPTH) return null
        return when (val type = contract.types[name]) {
            is TypeDecl.Entity -> {
                type.properties
            }
            is TypeDecl.Value -> {
                type.properties
            }
            is TypeDecl.Union -> {
                val variants =
                    type.variants.values.mapNotNull {
                        contract.types[it] as? TypeDecl.Entity
                    }

                val first = variants.firstOrNull()
                first?.properties?.filter { (property, declaration) ->
                    property in first.identity &&
                        variants.all {
                            property in it.identity &&
                                it.properties[property]?.type == declaration.type
                        }
                }
            }
            is TypeDecl.Projection -> {
                if (propertiesOf(type.source, depth + 1) == null) return null
                // Each projection property is the property its source path ends in.
                type.paths
                    .mapNotNull { (property, path) ->
                        resolvePath(type.source, path)?.let {
                            property to
                                it.property
                        }
                    }.toMap()
            }
            else -> {
                contract.shapes[name]?.let { propertiesOf(it.source, depth + 1) }
            }
        }
    }

    /** A resolved property path: its final property and whether it crossed a collection. */
    data class ResolvedPath(
        val property: Property,
        val crossesCollection: Boolean,
    )

    fun resolvePath(
        root: String,
        path: String,
    ): ResolvedPath? {
        var owner = root
        var crosses = false
        var property: Property? = null
        path.split('.').forEach { segment ->
            property = propertiesOf(owner)?.get(segment) ?: return null
            val expression = TypeExpression.parse(property!!.type) ?: return null
            val element =
                when (expression) {
                    is TypeExpression.ListOf -> expression.element.also { crosses = true }
                    is TypeExpression.SetOf -> expression.element.also { crosses = true }
                    else -> expression
                }

            // A scalar has no properties: a further segment does not resolve.
            owner = (element as? TypeExpression.Named)?.name ?: ""
        }

        return property?.let { ResolvedPath(it, crosses) }
    }
}

private const val MAX_DEPTH = 16
