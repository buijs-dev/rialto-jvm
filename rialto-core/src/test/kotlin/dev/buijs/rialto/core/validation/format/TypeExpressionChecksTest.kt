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
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

/** The type expressions of properties, wrappers and view slots (K2.1, K2.11), and the named types they use (K1.2). */
class TypeExpressionChecksTest {
    @TempDir
    lateinit var work: Path

    @TestFactory
    fun `each invalid type expression gives its code at its pointer`(): List<DynamicTest> =
        ShipmentExample(work).invalid(INVALID)

    @TestFactory
    fun `a map keyed by an enum is valid`(): List<DynamicTest> = ShipmentExample(work).valid(VALID)

    companion object {
        private const val NOTE = "/types/Shipment/properties/note/type"
        private const val REASON = "/types/Shipment/properties/rejectionReason/type"
        private const val FILE = "/types/DeliveryProof/properties/file/type"
        private const val SLOT = "/views/ShipmentDetail/slots/header/context"

        private val INVALID =
            listOf(
                ref(NOTE),
                ref(NOTE, "map<string, Nope>"),
                // A map key that names no type is unresolved; whether it is an enum is not asked then.
                ref(NOTE, "map<Nope, string>"),
                Case(NOTE, "map<Address, string>", Code.TYPE_EXPRESSION),
                Case(REASON, "decimal(2,3)", Code.TYPE_EXPRESSION),
                // The scalar of a wrapper is checked like the type of a property.
                Case("/types/ShipmentId/wraps", "decimal(2,3)", Code.TYPE_EXPRESSION),
                // The form of a type is checked here, not by the JSON Schema (K1.7): a nested collection is K006.
                Case(REASON, "list<list<string>>", Code.TYPE_EXPRESSION),
                Case(REASON, "map<boolean, string>", Code.TYPE_EXPRESSION),
                // More digits than an integer holds is an invalid type, not a crash.
                Case(REASON, "decimal(99999999999,2)", Code.TYPE_EXPRESSION),
                // A binary is never an element of a collection or a map value (K2.11).
                Case(FILE, "list<binary>", Code.BINARY),
                Case(FILE, "set<binary>", Code.BINARY),
                Case(FILE, "map<string, binary?>", Code.BINARY),
                // The context of a view slot is a type expression too.
                ref(SLOT),
                Case(SLOT, "list<list<Shipment>>", Code.TYPE_EXPRESSION),
            )

        private val VALID = listOf(mapOf(NOTE to "map<Status, string>"))
    }
}
