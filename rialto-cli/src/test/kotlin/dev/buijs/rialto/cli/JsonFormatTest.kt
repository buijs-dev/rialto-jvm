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

package dev.buijs.rialto.cli

import dev.buijs.rialto.core.document.ContractMapper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Path

class JsonFormatTest {
    private val contracts = Path.of(javaClass.getResource("/rialto-spec/examples/contracts")!!.toURI())

    private fun run(vararg args: String): Pair<Int, String> {
        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()
        val code = run(args.toList(), PrintStream(out), PrintStream(err))
        return code to out.toString()
    }

    @Test
    fun `a valid contract is one document with valid true`() {
        // given
        val contract = contracts.resolve("valid/minimal.yaml").toString()

        // when
        val (code, output) = run("validate", "--format", "json", contract)

        // then
        val json = ContractMapper.json.readTree(output)
        val diagnostics = json.path("contracts").single().path("diagnostics")
        assertThat(code).isEqualTo(0)
        assertThat(json.path("valid").asBoolean()).isTrue()
        assertThat(diagnostics.isEmpty).isTrue()
    }

    @Test
    fun `an invalid contract lists its diagnostics with code, requirement and position`() {
        // given
        val file = contracts.resolve("invalid/unknown-type.yaml")

        // when
        val (code, output) = run("validate", "--format", "json", file.toString())

        // then
        val diagnostic =
            ContractMapper.json
                .readTree(output)
                .path("contracts")
                .single()
                .path("diagnostics")
                .first { it.path("code").asString() == "RIALTO-K004" }

        assertThat(code).isEqualTo(1)
        assertThat(diagnostic.path("severity").asString()).isEqualTo("error")
        assertThat(diagnostic.path("requirement").asString()).isEqualTo("K1.2")
        assertThat(diagnostic.path("line").asInt()).isPositive()
        assertThat(diagnostic.path("pointer").asString()).startsWith("/")
    }

    @Test
    fun `an unknown format is wrong usage`() {
        // given
        val contract = contracts.resolve("valid/minimal.yaml").toString()

        // when
        val (code, _) = run("validate", "--format", "xml", contract)

        // then
        assertThat(code).isEqualTo(64)
    }

    @Test
    fun `a missing file is an entry with valid false and a diagnostic without code`() {
        // given
        val valid = contracts.resolve("valid/minimal.yaml").toString()

        // when
        val (code, output) = run("validate", "--format", "json", valid, "missing.rialto.yaml")

        // then
        val json = ContractMapper.json.readTree(output)
        assertThat(code).isEqualTo(1)
        assertThat(json.path("valid").asBoolean()).isFalse()
        assertThat(json.at("/contracts/0/valid").asBoolean()).isTrue()
        assertThat(json.at("/contracts/1/file").asString()).isEqualTo("missing.rialto.yaml")
        assertThat(json.at("/contracts/1/valid").asBoolean()).isFalse()
        val diagnostic = json.at("/contracts/1/diagnostics/0")
        assertThat(diagnostic.path("severity").asString()).isEqualTo("error")
        assertThat(diagnostic.path("code").isNull).isTrue()
        assertThat(diagnostic.path("message").asString()).isEqualTo("missing.rialto.yaml not found")
    }

    @Test
    fun `every diagnostic has the same fields, null when unknown`() {
        // given
        val file = contracts.resolve("invalid/not-a-mapping.yaml")

        // when
        val (_, output) = run("validate", "--format", "json", file.toString())

        // then
        val diagnostic = ContractMapper.json.readTree(output).at("/contracts/0/diagnostics/0")
        assertThat(diagnostic.propertyNames().asSequence().toList())
            .containsExactly(
                "severity",
                "code",
                "requirement",
                "file",
                "line",
                "column",
                "pointer",
                "message",
                "hint",
            )
        assertThat(diagnostic.path("code").asString()).isEqualTo("RIALTO-K001")
        assertThat(diagnostic.path("line").isNull).isTrue()
        assertThat(diagnostic.path("hint").isNull).isTrue()
    }
}
