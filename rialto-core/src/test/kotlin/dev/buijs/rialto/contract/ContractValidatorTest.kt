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

package dev.buijs.rialto.contract

import com.fasterxml.jackson.databind.node.ObjectNode
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
    private val spec = Path.of(System.getProperty("rialto.spec"))
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
                val result = ContractValidator.validate(file)
                assertThat(result.diagnostics).isEmpty()
                assertThat(result.valid).isTrue()
            }
        }

    @TestFactory
    fun `invalid test contracts produce their expected code`() =
        testContracts("invalid").map { file ->
            DynamicTest.dynamicTest(file.parent.name + "/" + file.name) {
                // Every leading `# expect: <code>` line names a code the contract must produce.
                val expected =
                    file
                        .readLines()
                        .takeWhile { it.startsWith("#") }
                        .filter { it.startsWith("# expect:") }
                        .map { it.substringAfter("# expect:").trim() }

                assertThat(expected).isNotEmpty()
                val result = ContractValidator.validate(file)
                assertThat(result.valid).isFalse()
                assertThat(result.diagnostics.map { it.code.id }).containsAll(expected)
            }
        }

    @Test
    fun `the Shipment example is valid`() {
        val result =
            ContractValidator.validate(
                shipment.resolve("shipment.rialto.yaml"),
            )
        assertThat(result.diagnostics).isEmpty()
        assertThat(result.contract!!.operations).containsKey("approveShipment")
    }

    @Test
    fun `the split Shipment bundles to the single file`() {
        val split = shipment.resolve("split/shipment.rialto.yaml")
        assertThat(ContractValidator.validate(split).diagnostics).isEmpty()

        val bundled =
            ContractFiles.read(split, Diagnostics())!!.root.deepCopy<ObjectNode>().apply {
                remove("sources")
            }

        val single =
            LocatedDocument
                .read(shipment.resolve("shipment.rialto.yaml"))
                .root
                .deepCopy<ObjectNode>()
                .apply { remove("sources") }

        assertThat(bundled).isEqualTo(single)
    }

    @Test
    fun `the bundled form written elsewhere keeps its sources`(
        @TempDir
        work: Path,
    ) {
        val output = work.resolve("out/shipment.rialto.yaml")
        val diagnostics = Diagnostics()
        val written =
            ContractFiles.bundle(
                shipment.resolve("split/shipment.rialto.yaml"),
                output,
                diagnostics,
            )
        assertThat(written).isTrue()
        assertThat(ContractValidator.validate(output).diagnostics).isEmpty()
    }

    @Test
    fun `a written contract keeps its extensions and leaves custom false out`(
        @TempDir
        work: Path,
    ) {
        val contract =
            ContractMapper.json.readValue(
                """{"rialto": "1.0.0", "info": {"name": "x", "version": "1.0.0"}, "x-acme-csv": {"Thing": {"columns": {"a": "b"}}},
                 "mappings": [{"source": "A", "target": "B", "direction": "both", "properties": {"a": {"from": "b"}}}]}""",
                Contract::class.java,
            )
        val file = work.resolve("x.rialto.yaml")
        ContractWriter.write(contract, file)
        val written = file.toFile().readText()
        assertThat(written).contains("x-acme-csv").doesNotContain("custom")
        assertThat(
            ContractMapper.json
                .treeToValue(
                    LocatedDocument.read(file).root,
                    Contract::class.java,
                ).extensions,
        ).containsKey("x-acme-csv")
    }

    @Test
    fun `a diagnostic in a fragment names the fragment`() {
        val result =
            ContractValidator.validate(
                contracts.resolve("invalid/fragment-duplicate/contract.rialto.yaml"),
            )
        val location = result.diagnostics.single().location
        assertThat(location.file.toString()).endsWith("parts/b.rialto.yaml")
        assertThat(location.pointer).isEqualTo("/types/Thing")
        assertThat(location.line).isEqualTo(3)
    }

    @Test
    fun `include paths match within a segment with star and across segments with two`() {
        assertThat(
            ContractFiles.glob("parts/*.rialto.yaml").matches("parts/a.rialto.yaml"),
        ).isTrue()
        assertThat(
            ContractFiles.glob("parts/*.rialto.yaml").matches("parts/x/a.rialto.yaml"),
        ).isFalse()
        assertThat(
            ContractFiles.glob("contract/**/*.rialto.yaml").matches("contract/a.rialto.yaml"),
        ).isTrue()
        assertThat(
            ContractFiles.glob("contract/**/*.rialto.yaml").matches("contract/x/y/a.rialto.yaml"),
        ).isTrue()
        assertThat(ContractFiles.glob("a.b.yaml").matches("aXb.yaml")).isFalse()
    }

    @Test
    fun `diagnostics carry line, column and pointer`() {
        val result = ContractValidator.validate(contracts.resolve("invalid/unknown-type.yaml"))
        val location = result.diagnostics.single().location
        assertThat(location.pointer).isEqualTo("/types/Thing/properties/owner/type")
        assertThat(location.line).isEqualTo(11)
        assertThat(
            result.diagnostics.single().toString(),
        ).startsWith("error[RIALTO-K004] unknown-type.yaml:11:")
    }

    @Test
    fun `the codes are those of the standard table, each with one of its requirements`() {
        // | `RIALTO-K001` | Document is not valid YAML or JSON, or is not a mapping | K1.7 |
        val row = Regex("""^\| `(RIALTO-[A-Z]\d{3})` \|.*\| ([^|]+) \|$""")
        val table =
            spec
                .resolve("spec/contract.md")
                .readLines()
                .mapNotNull { row.find(it) }
                .associate { it.groupValues[1] to it.groupValues[2].split(",").map(String::trim) }

        val codes = Code.entries.filter { it != Code.INTERNAL }

        assertThat(codes.map { it.id }).containsExactlyElementsOf(table.keys)
        codes.forEach { assertThat(table.getValue(it.id)).contains(it.requirement) }
    }

    @Test
    fun `the bundled schema equals the one in the specification`() {
        val bundled = javaClass.getResource("contract-1.0.0.schema.json")!!.readText()
        assertThat(bundled).isEqualTo(spec.resolve("spec/contract-1.0.0.schema.json").readText())
    }

    @Test
    fun `a written contract reads back to the same model and stays valid`(
        @org.junit.jupiter.api.io.TempDir
        work: Path,
    ) {
        val original =
            ContractValidator
                .validate(
                    shipment.resolve("shipment.rialto.yaml"),
                ).contract!!
        listOf("openapi.json", "openapi-v1.json", "asyncapi.json", "schema.sql").forEach {
            java.nio.file.Files
                .copy(shipment.resolve(it), work.resolve(it))
        }

        val written = work.resolve("shipment.rialto.yaml")
        ContractWriter.write(original, written)
        val reread = ContractValidator.validate(written)
        assertThat(reread.diagnostics).isEmpty()
        assertThat(reread.contract).isEqualTo(original)
    }
}
