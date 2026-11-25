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

import dev.buijs.rialto.core.diagnostics.Code
import dev.buijs.rialto.core.diagnostics.Location
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
import java.nio.file.Path
import kotlin.io.path.writeText

/** A YAML contract or fragment uses only what JSON can express (K1.11); a source document need not. */
class JsonYamlTest {
    @TempDir
    lateinit var work: Path

    private fun read(
        text: String,
        name: String = "c.rialto.yaml",
    ) = LocatedDocument.readMapping(work.resolve(name).also { it.writeText(text) })

    @ParameterizedTest(name = "[{0}]")
    @CsvSource(
        delimiter = '|',
        value = [
            "a: !shipment x                | 1 | 4  | The tag '!shipment' has no JSON form",
            "a: !shipment {b: 1}           | 1 | 4  | The tag '!shipment' has no JSON form",
            "a: !shipment [1]              | 1 | 4  | The tag '!shipment' has no JSON form",
            "a: ! x                        | 1 | 4  | The tag '!' has no JSON form",
            "a: !!binary aGk=              | 1 | 4  | The tag 'tag:yaml.org,2002:binary' has no JSON form",
            "a: &x 1                       | 1 | 4  | The anchor '&x' has no JSON form",
            "a: 1\\nb: *x                  | 2 | 4  | The alias '*x' has no JSON form",
            "a: {1: x}                     | 1 | 5  | The key '1', which is not a string, has no JSON form",
            "a: {true: x}                  | 1 | 5  | The key 'true', which is not a string, has no JSON form",
            "a: {~: x}                     | 1 | 5  | The key '~', which is not a string, has no JSON form",
            "a: {!!int 1: x}               | 1 | 5  | The key '1', which is not a string, has no JSON form",
            "a: [{b: 1, 2: c}]             | 1 | 12 | The key '2', which is not a string, has no JSON form",
        ],
    )
    fun `YAML that JSON cannot express is RIALTO-K001 at its place, for K1_11`(
        text: String,
        line: Int,
        column: Int,
        message: String,
    ) {
        // when
        val diagnostic = read(text.replace("\\n", "\n")).diagnostics.single()

        // then
        assertThat(diagnostic.code).isEqualTo(Code.UNREADABLE)
        assertThat(diagnostic.message).isEqualTo(message)
        assertThat(diagnostic.location).isEqualTo(Location(work.resolve("c.rialto.yaml"), line, column))
        assertThat(diagnostic.requirement).isEqualTo("K1.11")
    }

    @ParameterizedTest(name = "[{0}]")
    @ValueSource(
        strings = [
            "a: !!str 5",
            "a: !!int 5",
            "a: !!map {b: [!!seq [1], !!null ~, !!bool true, !!float 1.5]}",
            "'1': x",
            "\"true\": x",
            "!!str 1: x",
            "a: [1, {b: c}]\nd: {e: [f]}",
            "<<: x",
        ],
    )
    fun `the core schema tags and keys that are strings are JSON`(text: String) {
        // expect
        assertThat(read(text).diagnostics).isEmpty()
    }

    @Test
    fun `a mapping or a list as a key is not YAML Jackson reads, so it is RIALTO-K001 too`() {
        // expect
        assertThat(read("a: {? [b]: x}").diagnostics.single().code).isEqualTo(Code.UNREADABLE)
    }

    @Test
    fun `a source document may use all of YAML, and a JSON contract is not scanned`() {
        // expect: anchors and aliases are common in OpenAPI documents written in YAML
        assertThat(LocatedDocument.parse(Path.of("openapi.yaml"), "a: &x {b: 1}\nc: *x\n").at("/a/b")).isNotNull()
        assertThat(read("{\"a\": 1}", "c.rialto.json").diagnostics).isEmpty()
    }
}
