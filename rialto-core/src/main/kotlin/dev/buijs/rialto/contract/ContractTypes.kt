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
 * Looks up the properties of types and shapes, and resolves property paths.
 *
 * Several kinds of type have no property list of their own: a projection takes its properties from its source,
 * a shape from the type it shapes, and a union only shares the identity of its variants. Checks that ask
 * "does `destination.city` exist on this type?" use this class, so that answer is the same in every check.
 */
internal class ContractTypes(
    private val contract: Contract,
) {
    /**
     * The properties of the type or shape [name], following a projection or shape to its source; for a union the
     * identity properties every variant has with the same type. Null for an unknown name, an enum, or sources
     * that go round in a circle.
     */
    fun propertiesOf(name: String): Map<String, Property>? = propertiesOf(name, open = emptySet())

    /**
     * [open] holds what is being looked up, so that a circle ends where it meets itself: a projection or shape by
     * its name while its source is followed, and a projection property as `Type.property` while its path is
     * resolved.
     */
    private fun propertiesOf(
        name: String,
        open: Set<String>,
    ): Map<String, Property>? {
        // A projection or shape whose sources go round in a circle has no properties.
        if (name in open) {
            return null
        }

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
                if (propertiesOf(type.source, open + name) == null) {
                    return null
                }

                // Each projection property is the property its source path ends in.
                val properties = mutableMapOf<String, Property>()
                type.paths.keys.forEach { property ->
                    val resolved = propertyOf(name, property, open)
                    if (resolved != null) {
                        properties[property] = resolved
                    }
                }

                properties
            }
            else -> {
                contract.shapes[name]?.let { propertiesOf(it.source, open + name) }
            }
        }
    }

    /**
     * The property [property] of the type [owner]. For a projection it resolves the path of that property only, not
     * of every property: a path may pass through a type that refers back to the projection (`parent.id`, where
     * `parent` is the projection itself), and only a property whose path leads back to that same property is a
     * circle.
     */
    private fun propertyOf(
        owner: String,
        property: String,
        open: Set<String>,
    ): Property? {
        val projection =
            contract.types[owner] as? TypeDecl.Projection
                ?: return propertiesOf(owner, open)?.get(property)

        val path = projection.paths[property] ?: return null
        val key = "$owner.$property"
        if (key in open) {
            return null
        }

        return resolve(projection.source, path, crossed = false, open + key)?.property
    }

    /** A resolved property path: its final property and whether it crossed a collection. */
    data class ResolvedPath(
        val property: Property,
        val crossesCollection: Boolean,
    )

    /**
     * Resolves the dotted property [path] (`destination.city`) from the type [root], stepping through named types
     * and the elements of lists and sets; null when a segment is not a property of the type before it.
     */
    fun resolvePath(
        root: String,
        path: String,
    ): ResolvedPath? = resolve(root, path, crossed = false, open = emptySet())

    private fun resolve(
        owner: String,
        path: String,
        crossed: Boolean,
        open: Set<String>,
    ): ResolvedPath? {
        val property = propertyOf(owner, path.substringBefore('.'), open) ?: return null
        val expression = TypeExpression.parse(property.type) ?: return null
        val element =
            when (expression) {
                is TypeExpression.ListOf -> expression.element
                is TypeExpression.SetOf -> expression.element
                else -> expression
            }

        val crosses = crossed || element != expression
        if ('.' !in path) {
            return ResolvedPath(property, crosses)
        }

        // A scalar has no properties: a further segment does not resolve.
        val next = (element as? TypeExpression.Named)?.name ?: return null
        return resolve(next, path.substringAfter('.'), crosses, open)
    }
}
