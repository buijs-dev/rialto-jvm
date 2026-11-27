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

/** No name is both a type and a shape (K1.3). */
class NameChecksTest {
    @TempDir
    lateinit var work: Path

    @TestFactory
    fun `a shape named like a type gives K005 at the shape`(): List<DynamicTest> =
        ShipmentExample(work).invalid(INVALID)

    companion object {
        private val INVALID =
            listOf(
                Case(
                    pointer = "/shapes/Address",
                    value = mapOf("source" to "Address", "properties" to mapOf("street" to mapOf("role" to "caller"))),
                    code = Code.DUPLICATE_NAME,
                ),
            )
    }
}
