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

/** The references of auth and of the sources themselves. */
class AuthAndSourceReferencesTest {
    @TempDir
    lateinit var work: Path

    @TestFactory
    fun `each reference of auth or a source that points at nothing gives its code at its pointer`(): List<DynamicTest> =
        ShipmentExample(work).invalid(INVALID)

    companion object {
        private val INVALID =
            listOf(
                ref("/auth/clients/web/provider", "nope"),
                external("/auth/scopes/shipments:read/scheme", "api#/components/securitySchemes/nope"),
                external("/auth/apiKeys/warehouseKey/scheme", "api#/components/securitySchemes/nope"),
                ref("/sources/apiV1/successor", "nope"),
            )
    }
}
