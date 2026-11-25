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
import dev.buijs.rialto.core.diagnostics.Diagnostic
import dev.buijs.rialto.core.diagnostics.Location
import dev.buijs.rialto.core.diagnostics.Outcome
import dev.buijs.rialto.core.diagnostics.Severity
import dev.buijs.rialto.core.diagnostics.valueOrFail
import dev.buijs.rialto.core.document.ContractMapper
import dev.buijs.rialto.core.document.LocatedDocument
import dev.buijs.rialto.core.files.ContractFiles
import dev.buijs.rialto.core.files.ContractWriter
import dev.buijs.rialto.core.model.Contract
import dev.buijs.rialto.core.schema.ContractSchema
import dev.buijs.rialto.core.utilities.Glob
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkObject
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readLines
import kotlin.io.path.readText

class ContractValidatorTest {
    private val spec = Path.of(javaClass.getResource("/rialto-spec")!!.toURI())
    private val contracts = spec.resolve("examples/contracts")

    /** The Shipment example, a contract that uses the whole format, with its sources and its split form. */
    private val shipment = Path.of(javaClass.getResource("/shipment")!!.toURI())

    /** The test contracts of [directory]: single files and contracts over several files (their root). */
    private fun testContracts(directory: String): List<Path> {
        val dir = contracts.resolve(directory)
        val roots =
            dir
                .listDirectoryEntries()
                .filter { it.isDirectory() }
                .map { it.resolve("contract.rialto.yaml") }

        return (dir.listDirectoryEntries("*.yaml") + roots.filter { it.exists() }).sorted()
    }

    @TestFactory
    fun `valid test contracts have no errors`() =
        testContracts("valid").map { file ->
            DynamicTest.dynamicTest(file.parent.name + "/" + file.name) {
                // when
                val result = ContractValidator.validate(file)

                // then
                assertThat(result.diagnostics).isEmpty()
                assertThat(result).isInstanceOf(Outcome.Ok::class.java)
            }
        }

    @TestFactory
    fun `invalid test contracts produce their expected code`() =
        testContracts("invalid").map { file ->
            DynamicTest.dynamicTest(file.parent.name + "/" + file.name) {
                // given: every leading `# expect: <code>` line names a code the contract must produce.
                val expected =
                    file
                        .readLines()
                        .takeWhile { it.startsWith("#") }
                        .filter { it.startsWith("# expect:") }
                        .map { it.substringAfter("# expect:").trim() }
                assertThat(expected).isNotEmpty()

                // when
                val result = ContractValidator.validate(file)

                // then
                assertThat(result).isInstanceOf(Outcome.Nok::class.java)
                assertThat(result.diagnostics.map { it.code.id }).containsAll(expected)
            }
        }

    @Test
    fun `the Shipment example is valid`() {
        // when
        val result = ContractValidator.validate(shipment.resolve("shipment.rialto.yaml"))

        // then
        assertThat(result.diagnostics).isEmpty()
        assertThat(result.valueOrFail().contract.operations).containsKey("approveShipment")
    }

    @Test
    fun `the split Shipment bundles to the single file`() {
        // given
        val split = shipment.resolve("split/shipment.rialto.yaml")
        assertThat(ContractValidator.validate(split).diagnostics).isEmpty()

        // when
        val bundled =
            ContractFiles.read(split).valueOrFail().root.deepCopy().apply {
                remove("sources")
            }

        // then
        val single =
            LocatedDocument
                .read(shipment.resolve("shipment.rialto.yaml"))
                .root
                .deepCopy()
                .apply { remove("sources") }
        assertThat(bundled).isEqualTo(single)
    }

    @Test
    fun `the bundled form written elsewhere keeps its sources`(
        @TempDir
        work: Path,
    ) {
        // given
        val output = work.resolve("out/shipment.rialto.yaml")

        // when
        val written = ContractFiles.bundle(shipment.resolve("split/shipment.rialto.yaml"), output)

        // then
        assertThat(written).isEqualTo(Outcome.Ok(output))
        assertThat(ContractValidator.validate(output).diagnostics).isEmpty()
    }

    @Test
    fun `a written contract keeps its extensions and leaves custom false out`(
        @TempDir
        work: Path,
    ) {
        // given
        val contract =
            ContractMapper.json.readValue(
                """{"rialto": "1.0.0", "info": {"name": "x", "version": "1.0.0"}, "x-acme-csv": {"Thing": {"columns": {"a": "b"}}},
                 "mappings": [{"source": "A", "target": "B", "direction": "both", "properties": {"a": {"from": "b"}}}]}""",
                Contract::class.java,
            )
        val file = work.resolve("x.rialto.yaml")

        // when
        ContractWriter.write(contract, file)

        // then
        val written = file.toFile().readText()
        val reread = ContractMapper.json.treeToValue(LocatedDocument.read(file).root, Contract::class.java)
        assertThat(written).contains("x-acme-csv").doesNotContain("custom")
        assertThat(reread.extensions).containsKey("x-acme-csv")
    }

    @Test
    fun `a diagnostic in a fragment names the fragment`() {
        // when
        val result = ContractValidator.validate(contracts.resolve("invalid/fragment-duplicate/contract.rialto.yaml"))

        // then
        val location = result.diagnostics.single().location
        assertThat(location.file.toString()).endsWith("parts/b.rialto.yaml")
        assertThat(location.pointer).isEqualTo("/types/Thing")
        assertThat(location.line).isEqualTo(3)
    }

    @Test
    fun `include paths match within a segment with star and across segments with two`() {
        // expect
        assertThat(Glob.regex("parts/*.rialto.yaml").matches("parts/a.rialto.yaml")).isTrue()
        assertThat(Glob.regex("parts/*.rialto.yaml").matches("parts/x/a.rialto.yaml")).isFalse()
        assertThat(Glob.regex("contract/**/*.rialto.yaml").matches("contract/a.rialto.yaml")).isTrue()
        assertThat(Glob.regex("contract/**/*.rialto.yaml").matches("contract/x/y/a.rialto.yaml")).isTrue()
        assertThat(Glob.regex("a.b.yaml").matches("aXb.yaml")).isFalse()
    }

    @Test
    fun `diagnostics carry line, column and pointer`() {
        // when
        val result = ContractValidator.validate(contracts.resolve("invalid/unknown-type.yaml"))

        // then
        val diagnostic = result.diagnostics.single()
        assertThat(diagnostic.location.pointer).isEqualTo("/types/Thing/properties/owner/type")
        assertThat(diagnostic.location.line).isEqualTo(11)
        assertThat(diagnostic.toString()).startsWith("error[RIALTO-K004] unknown-type.yaml:11:")
    }

    @Test
    fun `the codes are those of the standard table, each with one of its requirements`() {
        // given: | `RIALTO-K001` | Document is not valid YAML or JSON, or is not a mapping | K1.7 |
        val row = Regex("""^\| `(RIALTO-[A-Z]\d{3})` \|.*\| ([^|]+) \|$""")
        val table =
            spec
                .resolve("spec/contract.md")
                .readLines()
                .mapNotNull { row.find(it) }
                .associate { it.groupValues[1] to it.groupValues[2].split(",").map(String::trim) }

        // when
        val codes = Code.entries

        // then
        assertThat(codes.map { it.id }).containsExactlyElementsOf(table.keys)
        codes.forEach { assertThat(table.getValue(it.id)).contains(it.requirement) }
    }

    @Test
    fun `the bundled schema equals the one in the specification`() {
        // given
        val bundled = ContractSchema::class.java.getResource("contract-1.0.0.schema.json")!!.readText()
        val specified = spec.resolve("spec/contract-1.0.0.schema.json").readText()

        // expect
        assertThat(bundled).isEqualTo(specified)
    }

    @Test
    fun `a written contract reads back to the same model and stays valid`(
        @org.junit.jupiter.api.io.TempDir
        work: Path,
    ) {
        // given
        val original = ContractValidator.validate(shipment.resolve("shipment.rialto.yaml")).valueOrFail().contract
        listOf("openapi.json", "openapi-v1.json", "asyncapi.json", "schema.sql").forEach {
            java.nio.file.Files
                .copy(shipment.resolve(it), work.resolve(it))
        }

        val written = work.resolve("shipment.rialto.yaml")

        // when
        ContractWriter.write(original, written)
        val reread = ContractValidator.validate(written)

        // then
        assertThat(reread.diagnostics).isEmpty()
        assertThat(reread.valueOrFail().contract).isEqualTo(original)
    }

    @Test
    fun `the checks over the model are those of the format, before any profile`() {
        // expect
        assertThat(ContractValidator.checks).containsExactly(FormatChecks)
    }

    @Test
    fun `a contract the model rejects after the JSON Schema stops before its sources are read`() {
        mockkObject(ContractValidator)
        try {
            // given
            val misfit = Diagnostic(Severity.ERROR, Code.SCHEMA, "misfit", Location(Path.of("c.yaml")))
            every { ContractValidator.model(any()) } returns Outcome.Nok(listOf(misfit))

            // when
            val result = ContractValidator.validate(shipment.resolve("shipment.rialto.yaml"))

            // then
            assertThat(result).isEqualTo(Outcome.Nok(listOf(misfit)))
        } finally {
            unmockkObject(ContractValidator)
        }
    }

    @Test
    fun `a misfit names the path into lists, and the key a contract misses at the top`() {
        // given
        val file = Path.of("c.yaml")
        val list = LocatedDocument.parse(file, "rialto: x\ninfo: {name: x, version: x}\nmappings: [{}]\n")
        val empty = LocatedDocument.parse(file, "")

        // when
        val inList =
            ContractValidator
                .model(list)
                .diagnostics
                .single()
                .message
        val atTop =
            ContractValidator
                .model(empty)
                .diagnostics
                .single()
                .message

        // then
        assertThat(inList).contains("does not fit the contract model at /mappings/0")
        assertThat(atTop)
            .startsWith("the contract passes the JSON Schema but does not fit the contract model at /rialto: ")
    }
}
