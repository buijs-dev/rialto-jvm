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

import dev.buijs.rialto.core.diagnostics.Diagnostic
import dev.buijs.rialto.core.document.ContractMapper
import dev.buijs.rialto.core.document.LocatedDocument
import dev.buijs.rialto.core.schema.ContractSchema
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.name
import kotlin.io.path.readText

/**
 * The contract snippets in the Markdown of the specification: each is valid against the JSON Schema.
 *
 * The specification explains the format with YAML blocks. A block that no longer fits the schema teaches the
 * reader a contract that every tool rejects, so this tooling checks the blocks of the specification it tests
 * against: the published artifact, or a checkout of rialto-spec.
 */
class SpecSnippetsTest {
    private val spec = Path.of(javaClass.getResource("/rialto-spec")!!.toURI())

    /** The top-level keys of a contract: a block with only these keys is a contract or a fragment of one. */
    private val sections: Set<String> =
        ContractMapper.json
            .readTree(spec.resolve("spec/contract-1.0.0.schema.json").toFile())
            .path("properties")
            .propertyNames()
            .asSequence()
            .toSet()

    private val yamlBlock = Regex("```yaml\n(.*?)```", RegexOption.DOT_MATCHES_ALL)

    private fun markdownFiles(): List<Path> =
        Files.walk(spec).use { paths ->
            paths
                .filter { it.name.endsWith(".md") }
                .sorted()
                .toList()
        }

    @TestFactory
    fun `every contract snippet in the Markdown of the specification is valid against the JSON Schema`() =
        markdownFiles().map { file ->
            DynamicTest.dynamicTest(spec.relativize(file).joinToString("/")) {
                // given
                val text = file.readText()

                yamlBlock.findAll(text).forEach { block ->
                    // when
                    val line = text.substring(0, block.range.first).count { it == '\n' } + 1
                    val found = findings(file, block.groupValues[1])

                    // then
                    assertThat(found).describedAs("the yaml block at line $line").isEmpty()
                }
            }
        }

    /**
     * The findings of the JSON Schema for one YAML [block], or none when the block is not a contract: the text of a
     * lock (a block whose first line names a `.rialto.lock`), or a block with other keys than the sections of a
     * contract.
     *
     * A block with `info` or `sources` is checked as a whole contract, any other as a fragment. A root's `include`
     * is left out, as in the bundled form, so the rest of the root is checked as a whole contract. A snippet leaves
     * out what does not matter to its point, so the format version and `info` are filled in.
     */
    private fun findings(
        file: Path,
        block: String,
    ): List<Diagnostic> {
        if (block.lineSequence().first().let { it.startsWith("# ") && it.endsWith(".rialto.lock") }) {
            return emptyList()
        }

        val snippet =
            ContractMapper.yaml
                .readTree(block)
                .asObjectOpt()
                .orElse(null) ?: return emptyList()
        // A root lists its fragments under `include`, which the bundled form drops (K5.1): the rest is checked.
        snippet.remove("include")

        if (!snippet.propertyNames().all { it in sections || it.startsWith("x-") }) {
            return emptyList()
        }

        val contract = ContractMapper.json.createObjectNode().put("rialto", "1.0.0")
        contract.setAll(snippet)
        val whole = contract.has("info") || contract.has("sources")
        if (whole && !contract.has("info")) {
            contract.putObject("info").put("name", "snippet").put("version", "1.0.0")
        }

        val document = LocatedDocument.parse(file, ContractMapper.yaml.writeValueAsString(contract))
        val schema = if (whole) ContractSchema.contract else ContractSchema.fragment
        return ContractSchema.check(schema, document.root, document)
    }
}
