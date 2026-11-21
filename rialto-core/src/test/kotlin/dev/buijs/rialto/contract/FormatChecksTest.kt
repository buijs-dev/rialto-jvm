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

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import org.junit.jupiter.api.io.TempDir
import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.ArrayNode
import tools.jackson.databind.node.ObjectNode
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeText

/**
 * The format checks over every section: each reference of the Shipment example, which uses the whole format, made
 * to point at nothing gives its code at its pointer, and nothing else.
 */
class FormatChecksTest {
    @TempDir
    lateinit var work: Path

    private val shipment = Path.of(javaClass.getResource("/shipment")!!.toURI())

    /** One change to the Shipment example: [value] at [pointer], and the one finding it must give. */
    private data class Case(
        val pointer: String,
        val value: Any,
        val code: Code,
        val at: String = pointer,
        /** A table that does not exist also leaves its columns unresolved: then the first finding is this one. */
        val first: Boolean = false,
    )

    private fun ref(
        pointer: String,
        value: Any = "Nope",
        at: String = pointer,
    ) = Case(pointer, value, Code.UNRESOLVED_REFERENCE, at)

    private fun external(
        pointer: String,
        value: Any,
        at: String = pointer,
    ) = Case(pointer, value, Code.SOURCE_REFERENCE, at)

    private val cases =
        listOf(
            // Types, shapes and names (K1.2, K1.3, K2.1).
            ref("/types/Recipient/variants/person"),
            ref("/types/Shipment/properties/note/type"),
            ref("/types/Shipment/properties/note/type", "map<string, Nope>"),
            // A map key that names no type is unresolved; whether it is an enum is not asked then.
            ref("/types/Shipment/properties/note/type", "map<Nope, string>"),
            Case(
                "/types/Shipment/properties/note/type",
                "map<Address, string>",
                Code.TYPE_EXPRESSION,
            ),
            Case(
                "/types/Shipment/properties/rejectionReason/type",
                "decimal(2,3)",
                Code.TYPE_EXPRESSION,
            ),
            // The scalar of a wrapper is checked like the type of a property.
            Case("/types/ShipmentId/wraps", "decimal(2,3)", Code.TYPE_EXPRESSION),
            // More digits than an integer holds is an invalid type, not a crash.
            Case(
                "/types/Shipment/properties/rejectionReason/type",
                "decimal(99999999999,2)",
                Code.TYPE_EXPRESSION,
            ),
            // A shape over a union has the identity its variants share; a projection over a projection its paths.
            ref(
                "/shapes/Nope",
                mapOf(
                    "source" to "HandlingUnit",
                    "properties" to mapOf("seal" to mapOf("role" to "caller")),
                ),
                "/shapes/Nope/properties/seal",
            ),
            ref(
                "/types/Nope",
                mapOf(
                    "kind" to "projection",
                    "source" to "ShipmentCity",
                    "properties" to mapOf("x" to "nope"),
                ),
                "/types/Nope/properties/x",
            ),
            ref("/types/ShipmentCity/source"),
            ref("/types/ShipmentCity/properties/city", "destination.nope"),
            ref("/types/ShipmentCity/properties/city", "destination.city.deeper"),
            ref("/types/ShipmentSummary/properties/0", "nope"),
            ref("/shapes/CreateShipment/properties/lines/shape"),
            // A shape over a type that does not exist has no properties to check.
            ref("/shapes/CreateShipment/source"),
            ref("/shapes/CountShipments/properties/nope", mapOf("role" to "caller")),
            Case(
                "/shapes/Address",
                mapOf(
                    "source" to "Address",
                    "properties" to mapOf("street" to mapOf("role" to "caller")),
                ),
                Code.DUPLICATE_NAME,
            ),
            // Operations.
            external(
                "/operations/getShipment/rest/api",
                "api#/paths/~1nope/get",
                "/operations/getShipment/rest",
            ),
            ref(
                "/operations/listShipments/rest/nope",
                "nope#/paths/~1shipments/get",
                "/operations/listShipments/rest",
            ),
            ref("/operations/createShipment/input"),
            ref("/operations/getShipment/output"),
            ref("/operations/getShipmentLines/output", "list<Nope>"),
            ref("/operations/deleteShipment/subject"),
            ref(
                "/operations/deleteShipment/subject",
                mapOf("type" to "Nope", "by" to "actor"),
                "/operations/deleteShipment/subject/type",
            ),
            ref("/operations/patchShipmentNote/deprecated/successor", "nope"),
            ref("/operations/cancelShipment/emits/0"),
            ref("/operations/listShipments/list/count/operation", "nope"),
            // Events, streams and subscriptions.
            ref("/events/ShipmentApproved/data"),
            external("/events/ShipmentApproved/channel", "events#/channels/nope"),
            ref("/streams/shipmentUpdates/events/0"),
            external("/streams/shipmentUpdates/channel", "events#/channels/nope"),
            ref("/subscriptions/notifyWarehouse/event"),
            external("/subscriptions/notifyWarehouse/delivery/deadLetter", "events#/channels/nope"),
            // Mappings.
            external("/mappings/0/source", "apiV1#/components/schemas/Nope"),
            ref("/mappings/0/target"),
            // A location in a document that is no JSON Pointer resolves to nothing.
            external("/mappings/0/source", "apiV1#components/schemas/Address"),
            // Storage.
            ref("/storage/Nope", mapOf("table" to "db#shipment")),
            external("/storage/Shipment/table", "db#nope").copy(first = true),
            external("/storage/Shipment/columns/id", "nope"),
            external("/storage/Shipment/columns/warehouse/join", "nope"),
            external("/storage/Shipment/columns/warehouse/key", "nope"),
            external(
                "/storage/Shipment/columns/warehouse",
                mapOf(
                    "join" to
                        listOf(
                            mapOf("column" to "warehouse_id", "table" to "db#warehouse"),
                            mapOf("column" to "nope", "table" to "db#warehouse"),
                        ),
                    "key" to "public_id",
                ),
                "/storage/Shipment/columns/warehouse/join/1/column",
            ),
            external(
                "/storage/Shipment/columns/warehouse",
                mapOf(
                    "join" to
                        listOf(
                            mapOf("column" to "warehouse_id", "table" to "db#warehouse"),
                            mapOf("column" to "storage_id", "table" to "db#warehouse"),
                        ),
                    "key" to "nope",
                ),
                "/storage/Shipment/columns/warehouse/key",
            ),
            external("/storage/Shipment/children/lines/order", "nope"),
            external("/storage/Shipment/children/customsDeclaration/ownerKey", "nope"),
            external("/storage/Shipment/links/tags/table", "db#nope").copy(first = true),
            external("/storage/Shipment/links/tags/ownerKey", "nope"),
            external("/storage/Shipment/links/tags/targetKey", "nope"),
            external("/storage/Shipment/links/scans/ownerKey", "nope"),
            external("/storage/Shipment/links/tags/order", "nope"),
            external("/storage/Shipment/version", "nope"),
            external("/storage/Shipment/storageKey", "nope"),
            external("/storage/Carrier/delete/soft", "nope"),
            external("/storage/Carrier/delete/by", "nope"),
            external("/storage/DeliveryProof/blobs/file/key", "nope"),
            external("/storage/DeliveryProof/blobs/file/sha256", "nope"),
            // Views and navigation.
            ref("/views/ShipmentDetail/load", "nope"),
            ref("/views/ShipmentProof/submit", "nope"),
            ref("/views/ShipmentDetail/fields/note/edit", "nope"),
            ref("/views/ShipmentDetail/fields/warehouse/link"),
            ref("/views/ShipmentDetail/fields/warehouse/options/operation", "nope"),
            ref("/views/ShipmentDetail/actions/approve/operation", "nope"),
            ref("/views/ShipmentDetail/tabs/0"),
            ref("/views/ShipmentList/list/operation", "nope"),
            ref("/views/ShipmentList/list/open"),
            ref("/views/ShipmentLines/list/actions/remove/operation", "nope"),
            ref("/views/ShipmentList/refreshOn/0"),
            ref("/navigation/home"),
            ref("/navigation/primary/0/view"),
            ref("/navigation/primary/0/badge", "nope"),
            // Auth and transitions.
            ref("/auth/clients/web/provider", "nope"),
            external("/auth/scopes/shipments:read/scheme", "api#/components/securitySchemes/nope"),
            external("/auth/apiKeys/warehouseKey/scheme", "api#/components/securitySchemes/nope"),
            ref("/types/Shipment/transitions/rules/approve/emits/0"),
        )

    @TestFactory
    fun `every reference that points at nothing gives its code at its pointer`(): List<DynamicTest> {
        listOf("openapi.json", "openapi-v1.json", "asyncapi.json", "schema.sql").forEach {
            Files.copy(shipment.resolve(it), work.resolve(it))
        }

        val original =
            ContractMapper.yaml.readTree(
                shipment.resolve("shipment.rialto.yaml").toFile(),
            ) as ObjectNode
        return cases.mapIndexed { index, case ->
            DynamicTest.dynamicTest("${case.pointer} = ${case.value}") {
                val document = original.deepCopy()
                set(document, case.pointer, ContractMapper.json.valueToTree(case.value))
                val file = work.resolve("case-$index.rialto.yaml")
                file.writeText(ContractMapper.yaml.writeValueAsString(document))

                val found =
                    ContractValidator.validate(file).diagnostics.map {
                        it.code to
                            it.location.pointer
                    }

                if (case.first) {
                    assertThat(found.first()).isEqualTo(case.code to case.at)
                    assertThat(found).allMatch {
                        it.first == case.code &&
                            it.second.startsWith(case.at.substringBeforeLast('/'))
                    }
                } else {
                    assertThat(found).containsExactly(case.code to case.at)
                }
            }
        }
    }

    /** An owned list child that keeps its order in a position column. */
    private val child =
        mapOf(
            "parentKey" to "shipment_id",
            "reconcile" to "identity",
            "onDelete" to "cascade",
            "order" to "position",
        )

    /** Changes to the Shipment example that stay valid: each a set of values by pointer. */
    private val valid =
        listOf(
            mapOf("/types/Shipment/properties/note/type" to "map<Status, string>"),
            // The position column of a child or link whose target has no table is checked by the profile, not here:
            // a property the entity lacks, one of a scalar type, one whose type is not stored, and the storage of a
            // projection.
            mapOf(
                "/storage/Shipment/children/nope" to child,
                "/storage/Shipment/children/note" to child,
                "/storage/Shipment/children/recipient" to child,
                "/storage/Shipment/links/nope" to mapOf("ownerKey" to "shipment_id"),
                "/storage/ShipmentSummary/children" to
                    mapOf("lines" to child),
            ),
            // A blob needs only its key column; a soft delete only its marker.
            mapOf(
                "/storage/DeliveryProof/blobs/file" to
                    mapOf("store" to "proofs", "key" to "file_key"),
                "/storage/Carrier/delete" to mapOf("soft" to "deleted_at"),
            ),
            mapOf(
                "/shapes/Nope" to
                    mapOf(
                        "source" to "HandlingUnit",
                        "properties" to mapOf("id" to mapOf("role" to "caller")),
                    ),
            ),
            mapOf(
                "/types/Nope" to
                    mapOf(
                        "kind" to "projection",
                        "source" to "ShipmentCity",
                        "properties" to mapOf("c" to "city"),
                    ),
            ),
            mapOf(
                "/types/Nope" to
                    mapOf(
                        "kind" to "projection",
                        "source" to "Shipment",
                        "properties" to mapOf("q" to "lines.quantity", "t" to "tags.name"),
                    ),
            ),
            // A union of value objects has no shared identity, and projections that go round in a circle have no
            // properties: neither has paths to check.
            mapOf(
                "/types/Nope" to
                    mapOf(
                        "kind" to "projection",
                        "source" to "Recipient",
                        "properties" to mapOf("n" to "name"),
                    ),
            ),
            mapOf(
                "/types/CycleA" to
                    mapOf(
                        "kind" to "projection",
                        "source" to "CycleB",
                        "properties" to mapOf("a" to "a"),
                    ),
                "/types/CycleB" to
                    mapOf(
                        "kind" to "projection",
                        "source" to "CycleA",
                        "properties" to mapOf("a" to "a"),
                    ),
            ),
        )

    @TestFactory
    fun `references that resolve through unions, projections and collections give nothing`(): List<DynamicTest> {
        listOf("openapi.json", "openapi-v1.json", "asyncapi.json", "schema.sql").forEach {
            Files.copy(shipment.resolve(it), work.resolve(it))
        }

        val original =
            ContractMapper.yaml.readTree(
                shipment.resolve("shipment.rialto.yaml").toFile(),
            ) as ObjectNode
        return valid.mapIndexed { index, changes ->
            DynamicTest.dynamicTest(changes.keys.joinToString()) {
                val document = original.deepCopy()
                changes.forEach { (pointer, value) ->
                    set(document, pointer, ContractMapper.json.valueToTree(value))
                }

                val file = work.resolve("valid-$index.rialto.yaml")
                file.writeText(ContractMapper.yaml.writeValueAsString(document))
                assertThat(ContractValidator.validate(file).diagnostics).isEmpty()
            }
        }
    }

    /** Sets [value] at [pointer], adding the last segment when it is a new key. */
    private fun set(
        root: JsonNode,
        pointer: String,
        value: JsonNode,
    ) {
        val parent = root.at(pointer.substringBeforeLast('/'))
        val key = pointer.substringAfterLast('/').replace("~1", "/").replace("~0", "~")
        when (parent) {
            is ObjectNode -> parent.set(key, value)
            is ArrayNode -> parent.set(key.toInt(), value)
            else -> error("No parent for $pointer")
        }
    }
}
