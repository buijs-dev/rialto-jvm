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
import kotlin.io.path.exists

class CommandsTest {
    private val contracts = Path.of(System.getProperty("rialto.spec"), "examples", "contracts")

    /** The exit code, stdout and stderr of one run. */
    private fun run(vararg args: String): Triple<Int, String, String> {
        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()
        val code = run(args.toList(), PrintStream(out), PrintStream(err))
        return Triple(code, out.toString(), err.toString())
    }

    @Test
    fun `an unknown command prints the usage`() {
        val (code, _, err) = run("generate", "contract.rialto.yaml")
        assertThat(code).isEqualTo(64)
        assertThat(err).startsWith("Usage:").contains("rialto validate")
    }

    @Test
    fun `validate reports the codes of an invalid contract and exits with 1`() {
        val (code, _, err) =
            run(
                "validate",
                contracts.resolve("invalid/unknown-type.yaml").toString(),
            )
        assertThat(code).isEqualTo(1)
        assertThat(err).contains("error[RIALTO-K004]")
    }

    @Test
    fun `bundle writes one valid document for a contract over several files`(
        @TempDir
        work: Path,
    ) {
        val output = work.resolve("bundled.rialto.yaml")
        val root = contracts.resolve("valid/fragments/contract.rialto.yaml").toString()
        val (code, out, _) = run("bundle", "--output", output.toString(), root)
        assertThat(code).isEqualTo(0)
        assertThat(out).contains("bundled into")
        assertThat(run("validate", output.toString()).first).isEqualTo(0)
    }

    @Test
    fun `sources update without remote sources writes no lock`() {
        val contract = contracts.resolve("valid/minimal.yaml")
        val (code, out, _) = run("sources", "update", contract.toString())
        assertThat(code).isEqualTo(0)
        assertThat(out).contains("0 remote source(s) updated")
        assertThat(contract.resolveSibling("rialto.lock").exists()).isFalse()
    }
}
