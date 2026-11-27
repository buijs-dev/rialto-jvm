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

import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

/** The paths of projections and the properties of shapes exist on their source (K1.2). */
class PropertyPathChecksTest {
    @TempDir
    lateinit var work: Path

    @TestFactory
    fun `each path that is not a property of its source gives K004 at its pointer`(): List<DynamicTest> =
        ShipmentExample(work).invalid(INVALID)

    @TestFactory
    fun `paths that resolve through unions, projections and collections give nothing`(): List<DynamicTest> =
        ShipmentExample(work).valid(VALID)

    companion object {
        private val INVALID =
            listOf(
                // A shape over a union has the identity its variants share; a projection over a projection its paths.
                ref("/shapes/Nope", shape("HandlingUnit", "seal"), "/shapes/Nope/properties/seal"),
                ref("/types/Nope", projection("ShipmentCity", "x" to "nope"), "/types/Nope/properties/x"),
                ref("/types/ShipmentCity/properties/city", "destination.nope"),
                ref("/types/ShipmentCity/properties/city", "destination.city.deeper"),
                // The list form is located by index.
                ref("/types/ShipmentSummary/properties/0", "nope"),
                ref("/shapes/CountShipments/properties/nope", mapOf("role" to "caller")),
            )

        private val VALID =
            listOf(
                mapOf("/shapes/Nope" to shape("HandlingUnit", "id")),
                mapOf("/types/Nope" to projection("ShipmentCity", "c" to "city")),
                mapOf("/types/Nope" to projection("Shipment", "q" to "lines.quantity", "t" to "tags.name")),
                // A union of value objects has no shared identity, and projections that go round in a circle have
                // no properties: neither has paths to check.
                mapOf("/types/Nope" to projection("Recipient", "n" to "name")),
                mapOf(
                    "/types/CycleA" to projection("CycleB", "a" to "a"),
                    "/types/CycleB" to projection("CycleA", "a" to "a"),
                ),
            )

        /** A projection of [source] with [paths], by property name. */
        private fun projection(
            source: String,
            vararg paths: Pair<String, String>,
        ) = mapOf("kind" to "projection", "source" to source, "properties" to mapOf(*paths))

        /** A shape of [source] with the one [property]. */
        private fun shape(
            source: String,
            property: String,
        ) = mapOf("source" to source, "properties" to mapOf(property to mapOf("role" to "caller")))
    }
}
