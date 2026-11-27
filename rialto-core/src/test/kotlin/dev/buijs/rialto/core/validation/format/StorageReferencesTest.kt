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

/** The references of storage: the stored type, its table and every column it names. */
class StorageReferencesTest {
    @TempDir
    lateinit var work: Path

    @Test
    fun `a column is a reference to the table it belongs to`() {
        // given
        val contract = contractOf(SHIPMENT_WITH_LINES)

        // expect
        assertThat(contract.storageReferences()).containsExactly(
            nameAt(Kind.TYPE, "Shipment", "/storage/Shipment"),
            externalAt("db#shipment", "/storage/Shipment/table"),
            externalAt("db#shipment.id", "/storage/Shipment/columns/id"),
            externalAt("db#shipment.warehouse_id", "/storage/Shipment/columns/warehouse/join"),
            externalAt("db#warehouse.public_id", "/storage/Shipment/columns/warehouse/key"),
            externalAt("db#shipment.id", "/storage/Shipment/children/lines/ownerKey"),
            // The position is a column of the child's table.
            externalAt("db#line.position", "/storage/Shipment/children/lines/order"),
            nameAt(Kind.TYPE, "Line", "/storage/Line"),
            externalAt("db#line", "/storage/Line/table"),
        )
    }

    @TestFactory
    fun `each table or column that does not exist gives its code at its pointer`(): List<DynamicTest> =
        ShipmentExample(work).invalid(INVALID)

    @TestFactory
    fun `storage that leaves out optional columns gives nothing`(): List<DynamicTest> =
        ShipmentExample(work).valid(VALID)

    companion object {
        /** A shipment with a plain and a joined column, and owned lines that keep their position. */
        private const val SHIPMENT_WITH_LINES = """
            types:
              Shipment: {kind: entity, identity: [id], properties: {id: {type: uuid}, lines: {type: list<Line>}}}
              Line: {kind: entity, identity: [id], properties: {id: {type: uuid}}}
            storage:
              Shipment:
                table: db#shipment
                columns:
                  id: id
                  warehouse: {join: warehouse_id, table: db#warehouse, key: public_id}
                children:
                  lines: {ownerKey: id, order: position}
              Line:
                table: db#line
            """

        /** An owned list child that keeps its order in a position column. */
        private val CHILD =
            mapOf(
                "parentKey" to "shipment_id",
                "reconcile" to "identity",
                "onDelete" to "cascade",
                "order" to "position",
            )

        private val INVALID =
            listOf(
                ref(
                    pointer = "/storage/Nope",
                    value = mapOf("table" to "db#shipment"),
                ),
                external(
                    pointer = "/storage/Shipment/table",
                    value = "db#nope",
                ).copy(first = true),
                external(
                    pointer = "/storage/Shipment/columns/id",
                    value = "nope",
                ),
                external(
                    pointer = "/storage/Shipment/columns/warehouse/join",
                    value = "nope",
                ),
                external(
                    pointer = "/storage/Shipment/columns/warehouse/key",
                    value = "nope",
                ),
                external(
                    pointer = "/storage/Shipment/columns/warehouse",
                    value = joinPath(column = "nope", key = "public_id"),
                    at = "/storage/Shipment/columns/warehouse/join/1/column",
                ),
                external(
                    pointer = "/storage/Shipment/columns/warehouse",
                    value = joinPath(column = "storage_id", key = "nope"),
                    at = "/storage/Shipment/columns/warehouse/key",
                ),
                external(
                    pointer = "/storage/Shipment/children/lines/order",
                    value = "nope",
                ),
                external(
                    pointer = "/storage/Shipment/children/customsDeclaration/ownerKey",
                    value = "nope",
                ),
                external(
                    pointer = "/storage/Shipment/links/tags/table",
                    value = "db#nope",
                ).copy(first = true),
                external(
                    pointer = "/storage/Shipment/links/tags/ownerKey",
                    value = "nope",
                ),
                external(
                    pointer = "/storage/Shipment/links/tags/targetKey",
                    value = "nope",
                ),
                external(
                    pointer = "/storage/Shipment/links/scans/ownerKey",
                    value = "nope",
                ),
                external(
                    pointer = "/storage/Shipment/links/tags/order",
                    value = "nope",
                ),
                external(
                    pointer = "/storage/Shipment/version",
                    value = "nope",
                ),
                external(
                    pointer = "/storage/Shipment/storageKey",
                    value = "nope",
                ),
                external(
                    pointer = "/storage/Carrier/delete/soft",
                    value = "nope",
                ),
                external(
                    pointer = "/storage/Carrier/delete/by",
                    value = "nope",
                ),
                external(
                    pointer = "/storage/DeliveryProof/blobs/file/key",
                    value = "nope",
                ),
                external(
                    pointer = "/storage/DeliveryProof/blobs/file/sha256",
                    value = "nope",
                ),
            )

        private val VALID =
            listOf(
                // The position column of a child or link whose target has no table is checked by the profile, not
                // here: a property the entity lacks, one of a scalar type, one whose type is not stored, and the
                // storage of a projection.
                mapOf(
                    "/storage/Shipment/children/nope" to CHILD,
                    "/storage/Shipment/children/note" to CHILD,
                    "/storage/Shipment/children/recipient" to CHILD,
                    "/storage/Shipment/links/nope" to mapOf("ownerKey" to "shipment_id"),
                    "/storage/ShipmentSummary/children" to mapOf("lines" to CHILD),
                ),
                // A blob needs only its key column; a soft delete only its marker.
                mapOf(
                    "/storage/DeliveryProof/blobs/file" to mapOf("store" to "proofs", "key" to "file_key"),
                    "/storage/Carrier/delete" to mapOf("soft" to "deleted_at"),
                ),
            )

        /** A join over two steps to the warehouse table: the second step's [column], then its [key]. */
        private fun joinPath(
            column: String,
            key: String,
        ) = mapOf(
            "join" to
                listOf(
                    mapOf("column" to "warehouse_id", "table" to "db#warehouse"),
                    mapOf("column" to column, "table" to "db#warehouse"),
                ),
            "key" to key,
        )
    }
}
