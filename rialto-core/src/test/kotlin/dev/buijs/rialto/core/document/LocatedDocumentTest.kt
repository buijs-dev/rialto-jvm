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
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
import tools.jackson.core.JacksonException
import java.nio.file.Path

/** Which parser [LocatedDocument] picks for a file, and how that parser is configured. */
class LocatedDocumentTest {
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = ["c.json", "c.JSON", "c.Json", "dir.yaml/c.json"])
    fun `a file ending in json is read as strict JSON`(name: String) {
        // when
        val document = LocatedDocument.parse(Path.of(name), """{"a": [1, "x"]}""")

        // then
        assertThat(document.at("/a/1")!!.stringValue()).isEqualTo("x")
        assertThatThrownBy { LocatedDocument.parse(Path.of(name), "{\"a\": 1 // comment\n}") }
            .isInstanceOf(JacksonException::class.java)
        assertThatThrownBy { LocatedDocument.parse(Path.of(name), "a: 1\n") }
            .isInstanceOf(JacksonException::class.java)
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = ["c.yaml", "c.yml", "c.YAML", "c", "c.txt", "c.json.yaml", "json"])
    fun `any other file is read as YAML`(name: String) {
        // when
        val document = LocatedDocument.parse(Path.of(name), "# comment\na: [1, x]\nyes: no\n")

        // then
        assertThat(document.at("/a/1")!!.stringValue()).isEqualTo("x")
        assertThat(document.at("/yes")!!.stringValue()).isEqualTo("no")
    }

    @Test
    fun `JSON in a YAML file is read as YAML, with the core schema`() {
        // when
        val document = LocatedDocument.parse(Path.of("c.yaml"), """{"a": 0444, "b": yes}""")

        // then
        assertThat(document.at("/a")!!.intValue()).isEqualTo(444)
        assertThat(document.at("/b")!!.stringValue()).isEqualTo("yes")
    }

    @Test
    fun `JSON keeps numbers that YAML would read differently`() {
        // when
        val document = LocatedDocument.parse(Path.of("c.json"), """{"big": 12345678901234567890, "f": 1e3}""")

        // then
        assertThat(document.at("/big")!!.bigIntegerValue().toString()).isEqualTo("12345678901234567890")
        assertThat(document.at("/f")!!.doubleValue()).isEqualTo(1000.0)
    }

    @ParameterizedTest(name = "{0}: {1}")
    @CsvSource(
        delimiter = '|',
        value = [
            "c.yaml | '- a'           | a list",
            "c.yaml | '~'             | null",
            "c.yaml | 'text'          | a single value",
            "c.json | '[1]'           | a list",
            "c.json | 'null'          | null",
            "c.json | '1'             | a single value",
        ],
    )
    fun `a document that is no mapping throws, saying what it is instead`(
        name: String,
        text: String,
        found: String,
    ) {
        assertThatThrownBy { LocatedDocument.parse(Path.of(name), text) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("The document is not a mapping but $found")
    }

    @Test
    fun `an empty document is an empty mapping`() {
        assertThat(LocatedDocument.parse(Path.of("c.yaml"), "").root.isEmpty).isTrue()
    }
}
