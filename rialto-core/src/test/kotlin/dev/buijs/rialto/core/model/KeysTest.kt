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

package dev.buijs.rialto.core.model

import dev.buijs.rialto.core.document.ContractMapper
import dev.buijs.rialto.core.schema.ContractSchema
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import tools.jackson.databind.JsonNode

/**
 * [Keys] against the JSON Schema of the specification: a key the code spells out is a key the format has.
 *
 * The compiler cannot tell a contract key from any other text, so a misspelled constant would read nothing and
 * point findings at the wrong line without a single error. The schema is the list of keys that exist.
 */
class KeysTest {
    /** The JSON Schema of the validator, which ContractValidatorTest keeps equal to the one of the specification. */
    private val schema: JsonNode =
        ContractSchema::class.java
            .getResourceAsStream("contract-1.0.0.schema.json")!!
            .use(ContractMapper.json::readTree)

    /** Each constant of [Keys] by name, read from the class so a new constant is checked without listing it here. */
    private val keys: Map<String, String> =
        Keys::class.java.declaredFields
            .filter { it.type == String::class.java }
            .associate { it.name to it.get(null) as String }

    /** Keywords of JSON Schema whose value is a mapping of names to schemas, not a schema itself. */
    private val named = setOf("properties", $$"$defs", "patternProperties")

    /** Every key the schema below [schema] declares under a `properties`, at any depth. */
    private fun declared(schema: JsonNode): Set<String> =
        schema
            .properties()
            .flatMap { (keyword, value) ->
                when {
                    keyword == "properties" -> value.propertyNames().toSet() + value.flatMap(::declared)
                    keyword in named || value.isArray -> value.flatMap(::declared)
                    else -> declared(value)
                }
            }.toSet()

    @Test
    fun `every key is a key the JSON Schema of the specification declares`() {
        // expect
        assertThat(keys).isNotEmpty()
        assertThat(declared(schema)).containsAll(keys.values)
    }

    @Test
    fun `every key has one constant`() {
        // expect
        assertThat(keys.values).doesNotHaveDuplicates()
    }
}
