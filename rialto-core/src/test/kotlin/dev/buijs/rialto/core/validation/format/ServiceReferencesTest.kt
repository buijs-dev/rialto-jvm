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

/** The references of operations, events, streams, subscriptions and mappings. */
class ServiceReferencesTest {
    @TempDir
    lateinit var work: Path

    @TestFactory
    fun `each reference of an operation that points at nothing gives its code at its pointer`(): List<DynamicTest> =
        ShipmentExample(work).invalid(OPERATIONS)

    @TestFactory
    fun `each reference of an event, stream or subscription to nothing gives its code`(): List<DynamicTest> =
        ShipmentExample(work).invalid(EVENTS)

    @TestFactory
    fun `each reference of a mapping that points at nothing gives its code at its pointer`(): List<DynamicTest> =
        ShipmentExample(work).invalid(MAPPINGS)

    companion object {
        private val OPERATIONS =
            listOf(
                external(
                    pointer = "/operations/getShipment/rest/api",
                    value = "api#/paths/~1nope/get",
                    at = "/operations/getShipment/rest",
                ),
                // A location that exists but is no OpenAPI operation: the path item, or an element inside the operation.
                external(
                    pointer = "/operations/getShipment/rest/api",
                    value = "api#/paths/~1shipments",
                    at = "/operations/getShipment/rest",
                ),
                external(
                    pointer = "/operations/getShipment/rest/api",
                    value = "api#/paths/~1shipments/get/responses",
                    at = "/operations/getShipment/rest",
                ),
                ref(
                    pointer = "/operations/listShipments/rest/nope",
                    value = "nope#/paths/~1shipments/get",
                    at = "/operations/listShipments/rest",
                ),
                ref(
                    pointer = "/operations/createShipment/input",
                ),
                ref(
                    pointer = "/operations/getShipment/output",
                ),
                ref(
                    pointer = "/operations/getShipmentLines/output",
                    value = "list<Nope>",
                ),
                ref(
                    pointer = "/operations/deleteShipment/subject",
                ),
                ref(
                    pointer = "/operations/deleteShipment/subject",
                    value = mapOf("type" to "Nope", "by" to "actor"),
                    at = "/operations/deleteShipment/subject/type",
                ),
                ref(
                    pointer = "/operations/patchShipmentNote/deprecated/successor",
                    value = "nope",
                ),
                ref(
                    pointer = "/operations/cancelShipment/emits/0",
                ),
                ref(
                    pointer = "/operations/listShipments/list/count/operation",
                    value = "nope",
                ),
            )

        private val EVENTS =
            listOf(
                ref(
                    pointer = "/events/ShipmentApproved/data",
                ),
                external(
                    pointer = "/events/ShipmentApproved/channel",
                    value = "events#/channels/nope",
                ),
                ref(
                    pointer = "/streams/shipmentUpdates/events/0",
                ),
                external(
                    pointer = "/streams/shipmentUpdates/channel",
                    value = "events#/channels/nope",
                ),
                ref(
                    pointer = "/subscriptions/notifyWarehouse/event",
                ),
                external(
                    pointer = "/subscriptions/notifyWarehouse/delivery/deadLetter",
                    value = "events#/channels/nope",
                ),
            )

        private val MAPPINGS =
            listOf(
                external(pointer = "/mappings/0/source", value = "apiV1#/components/schemas/Nope"),
                ref(pointer = "/mappings/0/target"),
                // A location in a document that is no JSON Pointer resolves to nothing.
                external(pointer = "/mappings/0/source", value = "apiV1#components/schemas/Address"),
            )
    }
}
