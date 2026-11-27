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
 * [Values] against the JSON Schema of the specification: a value the code spells out is a value the format allows.
 *
 * The compiler cannot tell a contract value from any other text, so a misspelled constant would make a contract
 * fail to read, or read as the wrong variant, without a single error. The schema is the list of values that exist.
 */
class ValuesTest {
    /** The JSON Schema of the validator, which ContractValidatorTest keeps equal to the one of the specification. */
    private val schema: JsonNode =
        ContractSchema::class.java
            .getResourceAsStream("contract-1.0.0.schema.json")!!
            .use(ContractMapper.json::readTree)

    /** Each constant of [Values] by name, read from the class so a new constant is checked without listing it. */
    private val values: Map<String, String> =
        Values::class.java.declaredFields
            .filter { it.type == String::class.java }
            .associate { it.name to it.get(null) as String }

    /** Every text the schema below [schema] allows in an `enum` or a `const`, at any depth. */
    private fun allowed(schema: JsonNode): Set<String> =
        schema
            .properties()
            .flatMap { (keyword, value) ->
                when {
                    keyword == "enum" && value.isArray -> value.filter { it.isString }.map { it.asString() }
                    keyword == "const" && value.isString -> listOf(value.asString())
                    value.isArray -> value.flatMap(::allowed)
                    else -> allowed(value)
                }
            }.toSet()

    @Test
    fun `every value is a value the JSON Schema of the specification allows`() {
        // expect
        assertThat(values).isNotEmpty()
        assertThat(allowed(schema)).containsAll(values.values)
    }

    @Test
    fun `every value has one constant`() {
        // expect
        assertThat(values.values).doesNotHaveDuplicates()
    }
}
