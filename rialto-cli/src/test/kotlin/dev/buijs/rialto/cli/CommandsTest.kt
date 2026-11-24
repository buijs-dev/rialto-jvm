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

import dev.buijs.rialto.core.diagnostics.Code
import dev.buijs.rialto.core.diagnostics.Diagnostic
import dev.buijs.rialto.core.diagnostics.Location
import dev.buijs.rialto.core.diagnostics.Severity
import dev.buijs.rialto.core.document.ContractMapper
import dev.buijs.rialto.core.sources.RemoteSources
import dev.buijs.rialto.core.sources.RialtoCache
import dev.buijs.rialto.core.utilities.sha256
import dev.buijs.rialto.core.validation.ContractValidator
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkObject
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

class CommandsTest {
    private val contracts = Path.of(javaClass.getResource("/rialto-spec/examples/contracts")!!.toURI())

    /** The exit code, stdout and stderr of one run. */
    private fun run(vararg args: String): Triple<Int, String, String> {
        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()
        val code = run(args.toList(), PrintStream(out), PrintStream(err))
        return Triple(code, out.toString(), err.toString())
    }

    @Test
    fun `an unknown command prints the usage`() {
        // when
        val (code, _, err) = run("generate", "contract.rialto.yaml")

        // then
        assertThat(code).isEqualTo(64)
        assertThat(err).startsWith("Usage:").contains("rialto validate")
    }

    @Test
    fun `validate reports the codes of an invalid contract and exits with 1`() {
        // given
        val contract = contracts.resolve("invalid/unknown-type.yaml").toString()

        // when
        val (code, _, err) = run("validate", contract)

        // then
        assertThat(code).isEqualTo(1)
        assertThat(err).contains("error[RIALTO-K004]")
    }

    @Test
    fun `bundle writes one valid document for a contract over several files`(
        @TempDir
        work: Path,
    ) {
        // given
        val output = work.resolve("bundled.rialto.yaml")
        val root = contracts.resolve("valid/fragments/contract.rialto.yaml").toString()

        // when
        val (code, out, _) = run("bundle", "--output", output.toString(), root)

        // then
        assertThat(code).isEqualTo(0)
        assertThat(out).contains("bundled into")
        assertThat(run("validate", output.toString()).first).isEqualTo(0)
    }

    @Test
    fun `bundle to a file that cannot be written says so and exits with 1`(
        @TempDir
        work: Path,
    ) {
        // given: the directory of the output is a file.
        val blocker = work.resolve("blocker")
        blocker.writeText("")
        val output = blocker.resolve("bundled.rialto.yaml")
        val root = contracts.resolve("valid/minimal.yaml").toString()

        // when
        val (code, out, err) = run("bundle", "--output", output.toString(), root)

        // then
        assertThat(code).isEqualTo(1)
        assertThat(out).isEmpty()
        assertThat(err).startsWith("error: cannot write $output: ")
        assertThat(err.lines().filter { it.isNotEmpty() }).hasSize(1)
    }

    @Test
    fun `an unexpected failure of the tool is reported as a bug and exits with 70`() {
        mockkObject(ContractValidator)
        try {
            // given
            every { ContractValidator.validate(any(), any()) } throws IllegalStateException("boom")
            val contract = contracts.resolve("valid/minimal.yaml").toString()

            // when
            val (code, out, err) = run("validate", contract)

            // then
            assertThat(code).isEqualTo(70)
            assertThat(out).isEmpty()
            assertThat(err)
                .startsWith("error: rialto failed unexpectedly. This is a bug in rialto;")
                .contains("java.lang.IllegalStateException: boom")
        } finally {
            unmockkObject(ContractValidator)
        }
    }

    @Test
    fun `sources update without remote sources writes no lock`() {
        // given
        val contract = contracts.resolve("valid/minimal.yaml")

        // when
        val (code, out, _) = run("sources", "update", contract.toString())

        // then
        assertThat(code).isEqualTo(0)
        assertThat(out).contains("0 remote source(s) updated")
        assertThat(contract.resolveSibling("rialto.lock").exists()).isFalse()
    }

    @TempDir
    lateinit var cache: Path

    private val originalFetch = RemoteSources.fetch
    private val url = "https://x.example/api.json"
    private val api = """{"openapi": "3.1.0", "paths": {}}"""

    @AfterEach
    fun tearDown() {
        RemoteSources.fetch = originalFetch
        System.clearProperty("rialto.cache")
    }

    /** A contract in [work] with one remote source, and a cache of its own. */
    private fun remote(work: Path): Path {
        System.setProperty("rialto.cache", cache.toString())
        return work.resolve("contract.rialto.yaml").also {
            it.writeText(
                "rialto: '1.0.0'\ninfo: {name: x, version: 1.0.0}\n" +
                    "sources:\n  api: {kind: openapi, path: '$url', version: 1.0.0, status: current}\n",
            )
        }
    }

    @Test
    fun `validate prints valid for each valid contract and fails when one is missing`(
        @TempDir
        work: Path,
    ) {
        // given
        val valid = contracts.resolve("valid/minimal.yaml").toString()
        val missing = work.resolve("missing.yaml").toString()

        // when
        val (code, out, err) = run("validate", valid, missing)

        // then
        assertThat(code).isEqualTo(1)
        assertThat(out).contains("$valid: valid")
        assertThat(err).contains("error: $missing not found")
        assertThat(run("validate", "--format", "text", valid).first).isZero()
    }

    @ParameterizedTest(name = "[{0}]")
    @ValueSource(
        strings = [
            "",
            "validate",
            "validate --format",
            "validate --format json",
            // --offline belongs to validate, after the command.
            "--offline validate contract.rialto.yaml",
            "sources update --offline contract.rialto.yaml",
            "bundle contract.rialto.yaml",
            "bundle contract.rialto.yaml --output",
            "bundle --output out.yaml",
            "bundle --output out.yaml a.yaml b.yaml",
            "sources",
            "sources pin contract.rialto.yaml",
            "sources update",
        ],
    )
    fun `wrong usage exits with 64`(args: String) {
        // when
        val (code, out, err) = run(*args.split(" ").filter(String::isNotEmpty).toTypedArray())

        // then
        assertThat(code).isEqualTo(64)
        assertThat(out).isEmpty()
        assertThat(err).startsWith("Usage:")
    }

    @Test
    fun `bundle of a contract with errors writes nothing and exits with 1`(
        @TempDir
        work: Path,
    ) {
        // given
        val output = work.resolve("bundled.rialto.yaml")
        val root = contracts.resolve("invalid/fragment-include-missing/contract.rialto.yaml").toString()

        // when
        val (code, out, err) = run("bundle", "--output", output.toString(), root)

        // then
        assertThat(code).isEqualTo(1)
        assertThat(out).doesNotContain("bundled into")
        assertThat(err).contains("error[RIALTO-K007]")
        assertThat(output.exists()).isFalse()
    }

    @Test
    fun `validate with offline reads remote sources from the cache only`(
        @TempDir
        work: Path,
    ) {
        // given
        val contract = remote(work).toString()
        RemoteSources.fetch = { error("offline") }

        // when
        val (code, _, err) = run("validate", "--offline", contract)

        // then
        assertThat(code).isEqualTo(1)
        assertThat(err).contains("error[RIALTO-S003]").contains("cannot be fetched offline")

        // given
        RemoteSources.fetch =
            { uri: URI -> if (uri.toString() == url) api.toByteArray() else error("404") }

        // expect
        assertThat(run("validate", contract).first).isZero()

        // given
        RemoteSources.fetch = { error("offline") }

        // expect
        assertThat(run("validate", "--offline", contract).first).isZero()
    }

    @Test
    fun `sources update prints each change and the count, and exits with 1 on an error`(
        @TempDir
        work: Path,
    ) {
        // given
        val contract = remote(work)
        RemoteSources.fetch = { api.toByteArray() }

        // when
        val (code, out, _) = run("sources", "update", contract.toString())

        // then
        assertThat(code).isZero()
        assertThat(out).contains("$url: new -> ${sha256(api.toByteArray())}")
        assertThat(out).contains("1 remote source(s) updated in rialto.lock")
        assertThat(work.resolve("rialto.lock").readText()).contains(url)

        // given
        RemoteSources.fetch = { error("down") }

        // when
        val (failed, failedOut, err) = run("sources", "update", contract.toString())

        // then
        assertThat(failed).isEqualTo(1)
        assertThat(err).contains("error[RIALTO-S003]")
        assertThat(failedOut).doesNotContain("updated in")
    }

    @Test
    fun `a rialto lock that is a directory fails validate and update with RIALTO-K010`(
        @TempDir
        work: Path,
    ) {
        // given
        val contract = remote(work).toString()
        Files.createDirectory(work.resolve("rialto.lock"))

        // when
        val (code, out, _) = run("validate", "--format", "json", contract)

        // then
        assertThat(code).isEqualTo(1)
        val json = ContractMapper.json.readTree(out)
        val codes = json.at("/contracts/0/diagnostics").values().map { it.path("code").asString() }
        assertThat(json.path("valid").asBoolean()).isFalse()
        assertThat(codes).containsExactly("RIALTO-K010")

        // when
        val (update, _, err) = run("sources", "update", contract)

        // then
        assertThat(update).isEqualTo(1)
        assertThat(err).contains("error[RIALTO-K010]")
    }

    @Test
    fun `errors are printed to stderr and warnings to stdout`() {
        // given
        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()
        val at = Location(Path.of("a.yaml"))
        val diagnostics =
            listOf(
                Diagnostic(Severity.ERROR, Code.SCHEMA, "wrong", at),
                Diagnostic(Severity.WARNING, Code.SCHEMA, "careful", at),
            )

        // when
        printDiagnostics(diagnostics, PrintStream(out), PrintStream(err))

        // then
        assertThat(err.toString()).isEqualTo("error[RIALTO-K002] a.yaml: wrong (K1.7)\n")
        assertThat(out.toString()).isEqualTo("warning[RIALTO-K002] a.yaml: careful (K1.7)\n")
    }
}
