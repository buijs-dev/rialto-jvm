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

package dev.buijs.rialto.core.conformance

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.assertj.core.api.Assertions.entry
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.Duration
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

/**
 * The conformance runner, against test contracts written for each test and [FakeTool] as the tool under test.
 */
class ConformanceTest {
    @TempDir
    lateinit var spec: Path

    private val java = Path.of(System.getProperty("java.home"), "bin", "java").toString()

    /** [FakeTool] as a command: this JVM with the class path of the tests. */
    private val tool =
        listOf(java, "-cp", System.getProperty("java.class.path"), FakeTool::class.java.name)

    /** Writes the test contract [name], such as `valid/a.yaml`, with these leading [lines]. */
    private fun contract(
        name: String,
        vararg lines: String,
    ): Path {
        val file = spec.resolve(Conformance.CONTRACTS).resolve(name)
        file.parent.createDirectories()
        file.writeText(lines.joinToString("\n", postfix = "\nrialto: '1.0.0'\n"))
        return file
    }

    private fun failures(command: List<String> = tool): Map<String, String?> =
        Conformance.run(spec, command).associate { it.name to it.failure }

    @Test
    fun `a valid test contract passes when the command exits with 0`() {
        // given
        contract("valid/accepted.yaml")
        contract("valid/rejected.yaml", "# tool: exit 3")

        // expect
        assertThat(failures()).containsExactly(
            entry("valid/accepted.yaml", null),
            entry("valid/rejected.yaml", "exit 3, expected 0"),
        )
    }

    @Test
    fun `an invalid test contract passes when the command fails and names every expected code`() {
        // given
        contract(
            "invalid/both.yaml",
            "# expect: RIALTO-K004",
            "# expect: RIALTO-K005",
            "# tool: print error[RIALTO-K004] on stdout",
            "# tool: error error[RIALTO-K005] on stderr",
            "# tool: exit 1",
        )

        // when
        val result = Conformance.run(spec, tool).single()

        // then
        assertThat(result.passed).isTrue()
        assertThat(result.failure).isNull()
        assertThat(result.output).contains("error[RIALTO-K004] on stdout", "error[RIALTO-K005] on stderr")
    }

    @Test
    fun `an invalid test contract fails when the command accepts it, misses a code, or the contract names none`() {
        // given
        contract("invalid/accepted.yaml", "# expect: RIALTO-K004")
        contract(
            "invalid/missing.yaml",
            "# expect: RIALTO-K004",
            "# expect: RIALTO-K005",
            "# tool: print RIALTO-K004",
            "# tool: exit 1",
        )
        // A longer code that starts with the expected one is another code.
        contract(
            "invalid/longer.yaml",
            "# expect: RIALTO-K004",
            "# tool: print RIALTO-K0041",
            "# tool: exit 1",
        )
        // Only the comment lines at the top count.
        contract(
            "invalid/no-code.yaml",
            "# tool: exit 1",
            "rialto: '1.0.0'",
            "# expect: RIALTO-K004",
        )

        // expect
        assertThat(failures()).containsExactly(
            entry("invalid/accepted.yaml", "exit 0, expected a failure with RIALTO-K004"),
            entry("invalid/longer.yaml", "missing RIALTO-K004"),
            entry("invalid/missing.yaml", "missing RIALTO-K005"),
            entry("invalid/no-code.yaml", "names no expected code (# expect: RIALTO-…)"),
        )
    }

    @Test
    fun `the valid test contracts come first, then the invalid ones, each in the order of their paths`() {
        // given
        contract(
            "invalid/b.yaml",
            "# expect: RIALTO-K001",
            "# tool: print RIALTO-K001",
            "# tool: exit 1",
        )
        contract("valid/b.yaml")
        contract("valid/a-several-files/contract.rialto.yaml")
        contract("valid/a-several-files/parts/part.yaml")
        // Neither a test contract: a file that is no YAML, and a directory without a root.
        contract("valid/notes.md")
        contract("valid/support/api.json")

        // when
        val names = Conformance.run(spec, tool).map { it.name }

        // then
        assertThat(names).containsExactly(
            "valid/a-several-files/contract.rialto.yaml",
            "valid/b.yaml",
            "invalid/b.yaml",
        )
    }

    @Test
    fun `the command runs in the directory of the test contract, with its path as the last argument`() {
        // given
        val file = contract("valid/a.yaml", "# tool: directory", "# tool: arguments")

        // when
        val output = Conformance.run(spec, tool + "validate").single().output

        // then
        assertThat(output.lines()).contains(file.parent.toRealPath().toString(), "validate | $file")
    }

    @Test
    fun `each contract placeholder in the command is replaced by the path of the test contract`() {
        // given
        val file = contract("valid/a.yaml", "# tool: arguments")

        // when
        val output = Conformance.run(spec, tool + "--in={contract}" + "{contract}").single().output

        // then
        assertThat(output.lines()).contains("--in=$file | $file")
    }

    @Test
    fun `a relative path to the program still works from the directory of each test contract`() {
        // given
        contract("valid/a.yaml")
        val relative =
            Path
                .of("")
                .toAbsolutePath()
                .relativize(Path.of(java))
                .toString()
        assertThat(relative).contains("/").doesNotStartWith("/")

        // when
        val results = failures(listOf(relative) + tool.drop(1))

        // then
        assertThat(results).containsExactly(entry("valid/a.yaml", null))
    }

    @Test
    fun `a command that cannot be started fails every test contract with the reason`() {
        // given
        contract("valid/a.yaml")

        // when
        val byName = Conformance.run(spec, listOf("no-such-rialto-tool")).single()
        val byPath = Conformance.run(spec, listOf("./no-such-directory/tool")).single()

        // then
        assertThat(byName.failure).startsWith("cannot start the command: ")
        assertThat(byName.output).isEmpty()
        assertThat(byPath.failure).startsWith("cannot start the command: ")
    }

    @Test
    fun `a command that does not end in time fails the test contract`() {
        // given
        contract("valid/slow.yaml", "# tool: sleep")

        // when
        val result = Conformance.run(spec, tool, timeout = Duration.ofSeconds(1)).single()

        // then
        assertThat(result.failure).isEqualTo("timed out after 1 s")
        assertThat(result.passed).isFalse()
    }

    @Test
    fun `several jobs give the same results in the same order`() {
        // given
        contract("valid/a.yaml")
        contract("valid/b.yaml", "# tool: exit 2")
        contract(
            "invalid/c.yaml",
            "# expect: RIALTO-K001",
            "# tool: print RIALTO-K001",
            "# tool: exit 1",
        )

        // when
        val parallel = Conformance.run(spec, tool, jobs = 3)
        val serial = Conformance.run(spec, tool, jobs = 1)

        // then
        assertThat(parallel).isEqualTo(serial)
    }

    @Test
    fun `a directory without test contracts gives no results`() {
        // expect
        assertThat(Conformance.run(spec, tool)).isEmpty()
    }

    @Test
    fun `an empty command and less than one job are refused`() {
        // expect
        assertThatThrownBy { Conformance.run(spec, emptyList()) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("The command is empty")
        assertThatThrownBy { Conformance.run(spec, tool, jobs = 0) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("jobs must be at least 1, but is 0")
    }
}
