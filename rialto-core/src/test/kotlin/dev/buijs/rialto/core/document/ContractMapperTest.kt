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

package dev.buijs.rialto.core.document

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import tools.jackson.core.JacksonException
import tools.jackson.core.ObjectReadContext
import tools.jackson.core.TokenStreamFactory
import tools.jackson.module.kotlin.readValue

/** The configuration of the factories and mappers in [ContractMapper]. */
class ContractMapperTest {
    private data class Ordered(
        val zeta: String,
        val alpha: String,
        val mid: String,
    )

    private data class Sparse(
        val name: String,
        val nothing: String? = null,
        val blank: String = "",
        val list: List<String> = emptyList(),
        val map: Map<String, String> = emptyMap(),
    )

    private data class Quoting(
        val plain: String,
        val number: String,
        val decimal: String,
        val bool: String,
        val tilde: String,
        val count: Int,
    )

    private class Empty

    private fun tokens(
        factory: TokenStreamFactory,
        text: String,
    ) = factory.createParser(ObjectReadContext.empty(), text).use { parser ->
        generateSequence { parser.nextToken() }.count()
    }

    @Test
    fun `the JSON factory reads plain JSON`() {
        // when
        val count = tokens(ContractMapper.jsonFactory, """{"a": [1, 2.5, "x", true, null]}""")

        // then
        assertThat(count).isEqualTo(10)
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(
        strings = [
            """{"a": 1 // comment
            }""",
            """{"a": 1 /* comment */}""",
            """{"a": 1 # comment
            }""",
            """{"a": [1, 2,]}""",
            """{"a": 1,}""",
            """{'a': 1}""",
            """{"a": 'x'}""",
            """{a: 1}""",
            """{"a": NaN}""",
            """{"a": 01}""",
            """{"a": +1}""",
            """{"a": .5}""",
        ],
    )
    fun `the JSON factory rejects what JSON does not allow`(text: String) {
        assertThatThrownBy { tokens(ContractMapper.jsonFactory, text) }.isInstanceOf(JacksonException::class.java)
    }

    @Test
    fun `the YAML factory reads YAML 1_2 with the core schema, without octal numbers`() {
        // when
        val tree = ContractMapper.yaml.readTree("yes: yes\nzero: 0444\noctal: 0o14\ntilde: ~\n")

        // then
        assertThat(tree["yes"].isString).isTrue()
        assertThat(tree["zero"].intValue()).isEqualTo(444)
        assertThat(tree["octal"].intValue()).isEqualTo(12)
        assertThat(tree["tilde"].isNull).isTrue()
    }

    @Test
    fun `YAML is written without a document start marker`() {
        // when
        val text = ContractMapper.yaml.writeValueAsString(Ordered("z", "a", "m"))

        // then
        assertThat(text).doesNotContain("---")
        assertThat(text).startsWith("zeta:")
    }

    @Test
    fun `YAML quotes only strings that would otherwise read as another value`() {
        // when
        val text =
            ContractMapper.yaml.writeValueAsString(
                Quoting(plain = "hello", number = "123", decimal = "1.5", bool = "true", tilde = "~", count = 7),
            )

        // then
        assertThat(text.lines()).contains(
            "plain: hello",
            "number: \"123\"",
            "decimal: \"1.5\"",
            "bool: \"true\"",
            "tilde: \"~\"",
            "count: 7",
        )
    }

    @Test
    fun `a string written as YAML reads back as the same string`() {
        // given
        val quoting = Quoting(plain = "hello", number = "123", decimal = "1.5", bool = "true", tilde = "~", count = 7)

        // when
        val read = ContractMapper.yaml.readValue<Quoting>(ContractMapper.yaml.writeValueAsString(quoting))

        // then
        assertThat(read).isEqualTo(quoting)
    }

    @Test
    fun `YAML is written in declaration order, not alphabetically`() {
        // when
        val text = ContractMapper.yaml.writeValueAsString(Ordered("z", "a", "m"))

        // then
        assertThat(text).isEqualTo("zeta: z\nalpha: a\nmid: m\n")
    }

    @Test
    fun `YAML leaves out absent and empty values`() {
        // when
        val text = ContractMapper.yaml.writeValueAsString(Sparse("x"))

        // then
        assertThat(text).isEqualTo("name: x\n")
    }

    @Test
    fun `YAML writes an object without properties instead of failing`() {
        // when
        val text = ContractMapper.yaml.writeValueAsString(Empty())

        // then
        assertThat(text.trim()).isEqualTo("{}")
    }

    @Test
    fun `the JSON mapper leaves unknown keys out of the model`() {
        // when
        val read = ContractMapper.json.readValue<Ordered>("""{"zeta": "z", "x-extra": 1, "alpha": "a", "mid": "m"}""")

        // then
        assertThat(read).isEqualTo(Ordered("z", "a", "m"))
    }

    @Test
    fun `the JSON mapper knows Kotlin defaults and writes in declaration order`() {
        // when
        val read = ContractMapper.json.readValue<Sparse>("""{"name": "x"}""")
        val text = ContractMapper.json.writeValueAsString(Ordered("z", "a", "m"))

        // then
        assertThat(read).isEqualTo(Sparse("x"))
        assertThat(text).isEqualTo("""{"zeta":"z","alpha":"a","mid":"m"}""")
    }

    @Test
    fun `the mappers are built on the factories of ContractMapper`() {
        assertThat(ContractMapper.json.tokenStreamFactory()).isSameAs(ContractMapper.jsonFactory)
        assertThat(ContractMapper.yaml.tokenStreamFactory()).isSameAs(ContractMapper.yamlFactory)
    }
}
