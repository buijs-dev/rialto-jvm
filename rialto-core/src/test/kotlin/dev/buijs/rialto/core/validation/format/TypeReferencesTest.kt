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

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

/** The references of types and shapes. */
class TypeReferencesTest {
    @TempDir
    lateinit var work: Path

    @Test
    fun `a section lists the references of its types in the order of the document`() {
        // given
        val contract = contractOf(TYPES_AND_SHAPES)

        // expect
        assertThat(contract.typeReferences()).containsExactly(
            nameAt(Kind.TYPE, "Person", "/types/Recipient/variants/person"),
            nameAt(Kind.TYPE, "Shipment", "/types/City/source"),
            nameAt(Kind.EVENT, "Approved", "/types/Shipment/transitions/rules/approve/emits/0"),
        )
        assertThat(contract.shapeReferences()).containsExactly(
            nameAt(Kind.TYPE, "Shipment", "/shapes/CreateShipment/source"),
            nameAt(Kind.SHAPE, "CreateLine", "/shapes/CreateShipment/properties/lines/shape"),
        )
    }

    @TestFactory
    fun `each reference of a type or shape that points at nothing gives K004 at its pointer`(): List<DynamicTest> =
        ShipmentExample(work).invalid(INVALID)

    companion object {
        /** A union, a projection, an entity with a transition, and a shape. */
        private const val TYPES_AND_SHAPES = """
            types:
              Recipient: {kind: union, discriminator: kind, variants: {person: Person}}
              City: {kind: projection, source: Shipment, properties: {city: destination.city}}
              Shipment:
                kind: entity
                identity: [id]
                properties: {id: {type: uuid}}
                transitions: {property: status, rules: {approve: {from: [draft], to: approved, emits: [Approved]}}}
            shapes:
              CreateShipment: {source: Shipment, properties: {lines: {role: caller, shape: CreateLine}}}
            """

        private val INVALID =
            listOf(
                ref("/types/Recipient/variants/person"),
                ref("/types/ShipmentCity/source"),
                ref("/types/Shipment/transitions/rules/approve/emits/0"),
                ref("/shapes/CreateShipment/properties/lines/shape"),
                // A shape over a type that does not exist has no properties to check.
                ref("/shapes/CreateShipment/source"),
            )
    }
}
