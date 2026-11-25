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

import dev.buijs.rialto.core.diagnostics.Code
import dev.buijs.rialto.core.validation.format.ShipmentExample
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

/**
 * The format checks as a whole: they run every rule over every section, in the order of the document. What each
 * section refers to and what each rule reports is tested per file in the `format` package.
 */
class FormatChecksTest {
    @TempDir
    lateinit var work: Path

    @Test
    fun `the Shipment example, which uses the whole format, gives nothing`() {
        assertThat(ShipmentExample(work).findings(emptyMap())).isEmpty()
    }

    @Test
    fun `the findings follow the order of the document, whatever rule reports them`() {
        // when
        val found = ShipmentExample(work).findings(ONE_IN_EACH_SECTION)

        // then
        assertThat(found).containsExactly(
            Code.DUPLICATE_NAME to "/shapes/Address",
            Code.TYPE_EXPRESSION to "/types/Shipment/properties/rejectionReason/type",
            Code.SOURCE_REFERENCE to "/storage/Shipment/version",
            Code.UNRESOLVED_REFERENCE to "/views/ShipmentDetail/load",
            Code.UNRESOLVED_REFERENCE to "/sources/apiV1/successor",
        )
    }

    companion object {
        /** One finding in each of five sections, listed in the reverse order of the document. */
        private val ONE_IN_EACH_SECTION =
            mapOf(
                "/sources/apiV1/successor" to "nope",
                "/views/ShipmentDetail/load" to "nope",
                "/storage/Shipment/version" to "nope",
                "/types/Shipment/properties/rejectionReason/type" to "decimal(2,3)",
                "/shapes/Address" to mapOf("source" to "Address", "properties" to emptyMap<String, Any>()),
            )
    }
}
