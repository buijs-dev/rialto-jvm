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

/** The references of views and navigation. */
class PresentationReferencesTest {
    @TempDir
    lateinit var work: Path

    @TestFactory
    fun `each reference of a view that points at nothing gives K004 at its pointer`(): List<DynamicTest> =
        ShipmentExample(work).invalid(VIEWS)

    @TestFactory
    fun `each reference of the navigation that points at nothing gives K004 at its pointer`(): List<DynamicTest> =
        ShipmentExample(work).invalid(NAVIGATION)

    companion object {
        private val VIEWS =
            listOf(
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
            )

        private val NAVIGATION =
            listOf(
                ref("/navigation/home"),
                ref("/navigation/primary/0/view"),
                ref("/navigation/primary/0/badge", "nope"),
            )
    }
}
