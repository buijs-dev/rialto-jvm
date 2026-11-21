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

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

/**
 * `rialto conformance`, with this command line itself as the tool under test: started as a program of its own, on
 * this JVM with the class path of the tests.
 */
class ConformanceCommandTest {
    @TempDir
    lateinit var work: Path

    private val rialto =
        arrayOf(
            Path.of(System.getProperty("java.home"), "bin", "java").toString(),
            "-cp",
            System.getProperty("java.class.path"),
            "dev.buijs.rialto.cli.MainKt",
        )

    /** The exit code, stdout and stderr of one run. */
    private fun run(vararg args: String): Triple<Int, String, String> {
        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()
        val code = run(args.toList(), PrintStream(out), PrintStream(err))
        return Triple(code, out.toString(), err.toString())
    }

    /** A specification in [work] with one valid test contract that is valid, and one that is not. */
    private fun specWithAFailure(): String {
        val valid = work.resolve("examples/contracts/valid").createDirectories()
        valid
            .resolve(
                "accepted.yaml",
            ).writeText("rialto: '1.0.0'\ninfo: {name: x, version: 1.0.0}\n")
        valid.resolve("rejected.yaml").writeText("- a list\n")
        return work.toString()
    }

    @Test
    fun `this command line passes every test contract of the specification`() {
        val (code, out, err) =
            run(
                "conformance",
                "--spec",
                System.getProperty("rialto.spec"),
                "--jobs",
                "8",
                "--",
                *rialto,
                "validate",
                "{contract}",
            )

        assertThat(err).isEmpty()
        assertThat(
            out.lines(),
        ).contains("PASS valid/minimal.yaml", "PASS invalid/unknown-type.yaml")
        assertThat(out.lines().filter { it.startsWith("FAIL") }).isEmpty()
        assertThat(
            out,
        ).containsPattern("""\n\n\d+ passed, 0 failed; command: .* validate \{contract}\n$""")
        assertThat(code).isZero()
    }

    @Test
    fun `a test contract that fails is reported with the reason, and the exit code is 1`() {
        val (code, out, _) =
            run(
                "conformance",
                "--spec",
                specWithAFailure(),
                "--",
                *rialto,
                "validate",
            )

        assertThat(out.lines()).contains(
            "PASS valid/accepted.yaml",
            "FAIL valid/rejected.yaml: exit 1, expected 0",
            "1 passed, 1 failed; command: ${rialto.joinToString(" ")} validate",
        )
        assertThat(out).doesNotContain("RIALTO-K001")
        assertThat(code).isEqualTo(1)
    }

    @Test
    fun `verbose shows the output of a failing run under its line`() {
        val (_, out, _) =
            run("conformance", "--spec", specWithAFailure(), "--verbose", "--", *rialto, "validate")

        assertThat(out.lines()).containsSequence(
            "FAIL valid/rejected.yaml: exit 1, expected 0",
            "     error[RIALTO-K001] rejected.yaml: The document is not a mapping but a list (K1.7)",
        )
    }

    @Test
    fun `verbose shows nothing more for a run without output`() {
        val (code, out, _) =
            run(
                "conformance",
                "--spec",
                specWithAFailure(),
                "--verbose",
                "--",
                "no-such-rialto-tool",
            )

        assertThat(out.lines().filter { it.isNotEmpty() }).hasSize(3).allMatch {
            it.startsWith("FAIL valid/") || it.startsWith("0 passed, 2 failed")
        }

        assertThat(out).contains("cannot start the command")
        assertThat(code).isEqualTo(1)
    }

    @Test
    fun `the arguments after the separator belong to the command, also when they are options of this command line`() {
        val (code, out, _) =
            run("conformance", "--spec", specWithAFailure(), "--", *rialto, "validate", "--offline")

        assertThat(out).contains("command: ${rialto.joinToString(" ")} validate --offline")
        assertThat(code).isEqualTo(1)
    }

    @Test
    fun `a specification without test contracts is an error`() {
        val (code, out, err) =
            run(
                "conformance",
                "--spec",
                work.toString(),
                "--",
                "rialto",
                "validate",
            )

        assertThat(code).isEqualTo(1)
        assertThat(out).isEmpty()
        assertThat(
            err.trim(),
        ).isEqualTo("error: no test contracts in ${work.resolve("examples/contracts")}")
    }

    @Test
    fun `wrong usage exits with 64`() {
        listOf(
            arrayOf("conformance"),
            arrayOf("conformance", "--spec", "spec"),
            arrayOf("conformance", "--spec", "spec", "--"),
            arrayOf("conformance", "--", "rialto", "validate"),
            arrayOf("conformance", "--spec", "--", "rialto", "validate"),
            arrayOf("conformance", "--spec", "spec", "--jobs", "--", "rialto"),
            arrayOf("conformance", "--spec", "spec", "--jobs", "many", "--", "rialto"),
            arrayOf("conformance", "--spec", "spec", "--jobs", "0", "--", "rialto"),
            arrayOf("conformance", "--spec", "spec", "--level", "starter", "--", "rialto"),
        ).forEach { args ->
            val (code, out, err) = run(*args)

            assertThat(code).describedAs(args.joinToString(" ")).isEqualTo(64)
            assertThat(out).isEmpty()
            assertThat(err).startsWith("Usage:").contains("rialto conformance --spec <dir>")
        }
    }
}
