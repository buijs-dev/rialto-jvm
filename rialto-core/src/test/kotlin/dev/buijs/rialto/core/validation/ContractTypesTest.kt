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

package dev.buijs.rialto.core.validation

import dev.buijs.rialto.core.model.Contract
import dev.buijs.rialto.core.model.Info
import dev.buijs.rialto.core.model.Property
import dev.buijs.rialto.core.model.Shape
import dev.buijs.rialto.core.model.TypeDecl
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.entry
import org.junit.jupiter.api.Test

/** The properties of each kind of type and shape, and the resolution of property paths through them. */
class ContractTypesTest {
    private fun entity(
        vararg properties: Pair<String, String>,
        identity: List<String> = listOf("id"),
    ) = TypeDecl.Entity(
        identity,
        properties =
            properties.associate { (name, type) ->
                name to
                    Property(type)
            },
    )

    private val types =
        mapOf(
            "Order" to
                entity(
                    "id" to "uuid",
                    "lines" to "list<Line>",
                    "tags" to "set<Tag>",
                    "note" to "string",
                ),
            "Line" to entity("id" to "uuid", "quantity" to "int32"),
            "Tag" to entity("id" to "uuid", "name" to "string"),
            "Broken" to entity("id" to "list<>"),
            "Address" to TypeDecl.Value(properties = mapOf("city" to Property("string"))),
            "Status" to TypeDecl.Enum(listOf("open")),
            "Party" to TypeDecl.Union("kind", mapOf("p" to "Person", "c" to "Company")),
            "Person" to entity("id" to "uuid", "name" to "string", identity = listOf("id", "name")),
            "Company" to entity("id" to "uuid", "name" to "int32", identity = listOf("id", "name")),
            "Guest" to entity("id" to "string"),
            "Keyless" to entity("name" to "string", identity = listOf("id", "name")),
            "Values" to TypeDecl.Union("kind", mapOf("a" to "Address")),
            "Summary" to TypeDecl.Projection("Order", mapOf("id" to "id", "city" to "nope.city")),
            "Nested" to TypeDecl.Projection("Summary", mapOf("id" to "id")),
            "Orphan" to TypeDecl.Projection("Nope", mapOf("id" to "id")),
            "CycleA" to TypeDecl.Projection("CycleB", mapOf("id" to "id")),
            "CycleB" to TypeDecl.Projection("CycleA", mapOf("id" to "id")),
        )

    private val lookup =
        ContractTypes(
            Contract(
                rialto = "1.0.0",
                info = Info("orders", "1.0.0"),
                types = types,
                shapes = mapOf("CreateOrder" to Shape("Order"), "Lost" to Shape("Nope")),
            ),
        )

    @Test
    fun `entities and value objects have their own properties`() {
        // expect
        assertThat(lookup.propertiesOf("Line")!!.keys).containsExactly("id", "quantity")
        assertThat(lookup.propertiesOf("Address")!!.keys).containsExactly("city")
    }

    @Test
    fun `a union has the identity properties all its entity variants share with the same type`() {
        // given: Guest's id is a string, Person's a uuid; Guest has no name.
        val withGuest =
            types + ("Party" to TypeDecl.Union("kind", mapOf("p" to "Person", "g" to "Guest")))
        // An identity that is not one of the properties (RIALTO-P001) shares nothing.
        val keyless =
            types + ("Party" to TypeDecl.Union("kind", mapOf("p" to "Person", "k" to "Keyless")))

        // when
        val shared = lookup.propertiesOf("Party")
        val sharedWithGuest = ContractTypes(Contract("1.0.0", Info("o", "1"), types = withGuest)).propertiesOf("Party")
        val sharedWithKeyless = ContractTypes(Contract("1.0.0", Info("o", "1"), types = keyless)).propertiesOf("Party")

        // then
        assertThat(shared!!.keys).containsExactly("id")
        assertThat(sharedWithGuest).isEmpty()
        assertThat(sharedWithKeyless).containsOnlyKeys("name")
    }

    @Test
    fun `a union of value objects has no properties`() {
        // expect
        assertThat(lookup.propertiesOf("Values")).isNull()
    }

    @Test
    fun `a projection has the properties its paths end in, also over another projection`() {
        // expect
        assertThat(lookup.propertiesOf("Summary")).containsOnlyKeys("id")
        assertThat(lookup.propertiesOf("Nested")).containsOnlyKeys("id")
    }

    @Test
    fun `a projection over nothing or in a circle has no properties`() {
        // expect
        assertThat(lookup.propertiesOf("Orphan")).isNull()
        assertThat(lookup.propertiesOf("CycleA")).isNull()
    }

    @Test
    fun `a projection path resolves through a type that refers back to the projection`() {
        // given: Node.parent is a NodeView, and NodeView.parentId is parent.id: the id of a NodeView, which is Node.id.
        val back =
            mapOf(
                "Node" to entity("id" to "uuid", "parent" to "NodeView"),
                "NodeView" to
                    TypeDecl.Projection("Node", mapOf("id" to "id", "parentId" to "parent.id")),
            )

        // when
        val lookup = ContractTypes(Contract("1.0.0", Info("o", "1"), types = back))

        // then
        assertThat(lookup.propertiesOf("NodeView"))
            .containsExactly(entry("id", Property("uuid")), entry("parentId", Property("uuid")))
        assertThat(lookup.resolvePath("Node", "parent.parentId")!!.property).isEqualTo(Property("uuid"))
    }

    @Test
    fun `a projection property whose path leads back to itself does not resolve`() {
        // given
        val circle =
            mapOf(
                "Node" to entity("id" to "uuid", "parent" to "NodeView"),
                "NodeView" to
                    TypeDecl.Projection("Node", mapOf("id" to "id", "again" to "parent.again")),
            )

        // when
        val lookup = ContractTypes(Contract("1.0.0", Info("o", "1"), types = circle))

        // then
        assertThat(lookup.propertiesOf("NodeView")).containsOnlyKeys("id")
        assertThat(lookup.resolvePath("Node", "parent.again")).isNull()
        assertThat(lookup.resolvePath("Node", "parent.id")).isNotNull()
    }

    @Test
    fun `a shape has the properties of its source, and an enum or unknown name none`() {
        // expect
        assertThat(lookup.propertiesOf("CreateOrder")!!.keys).contains("lines")
        assertThat(lookup.propertiesOf("Lost")).isNull()
        assertThat(lookup.propertiesOf("Status")).isNull()
        assertThat(lookup.propertiesOf("Nope")).isNull()
    }

    @Test
    fun `a path resolves through named types and tells whether it crossed a list or a set`() {
        // expect
        assertThat(lookup.resolvePath("Order", "note")).isEqualTo(ContractTypes.ResolvedPath(Property("string"), false))
        assertThat(lookup.resolvePath("Order", "lines.quantity"))
            .isEqualTo(ContractTypes.ResolvedPath(Property("int32"), true))
        assertThat(lookup.resolvePath("Order", "tags.name")!!.crossesCollection).isTrue()
    }

    @Test
    fun `a path does not resolve past a scalar, through an unknown property or an invalid type`() {
        // expect
        assertThat(lookup.resolvePath("Order", "note.length")).isNull()
        assertThat(lookup.resolvePath("Order", "nope")).isNull()
        assertThat(lookup.resolvePath("Broken", "id")).isNull()
        assertThat(lookup.resolvePath("Nope", "id")).isNull()
    }
}
