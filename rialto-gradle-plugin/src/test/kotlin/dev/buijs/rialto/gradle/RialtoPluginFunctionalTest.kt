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

package dev.buijs.rialto.gradle

import org.assertj.core.api.Assertions.assertThat
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class RialtoPluginFunctionalTest {
    @TempDir
    lateinit var project: File

    private val contracts = testContracts()

    /** A project whose contract is [contract], a test contract copied with the support documents next to it. */
    private fun setup(contract: String) {
        contracts.copyRecursively(project.resolve("contracts"), overwrite = true)
        project.resolve("settings.gradle.kts").writeText("rootProject.name = \"sample\"\n")
        project.resolve("build.gradle.kts").writeText(
            """
            plugins {
                id("dev.buijs.rialto")
            }

            rialto {
                contract.set(file("contracts/$contract"))
            }
            """.trimIndent(),
        )
    }

    private fun runner(vararg arguments: String) =
        GradleRunner
            .create()
            .withProjectDir(project)
            .withPluginClasspath()
            .withArguments(*arguments, "--stacktrace")

    @Test
    fun `validates a contract over several files, and is up to date the second time`() {
        setup("valid/fragments/contract.rialto.yaml")
        assertThat(runner("rialtoValidate").build().task(":rialtoValidate")?.outcome)
            .isEqualTo(TaskOutcome.SUCCESS)
        assertThat(runner("rialtoValidate").build().task(":rialtoValidate")?.outcome)
            .isEqualTo(TaskOutcome.UP_TO_DATE)
    }

    @Test
    fun `fails on an invalid contract with its diagnostic`() {
        setup("invalid/unknown-type.yaml")
        val result = runner("rialtoValidate").buildAndFail()
        assertThat(result.output).contains("error[RIALTO-K004]").contains("Rialto found 1 error(s)")
    }

    @Test
    fun `revalidates when a fragment changes`() {
        setup("valid/fragments/contract.rialto.yaml")
        runner("rialtoValidate").build()
        val fragment = project.resolve("contracts/valid/fragments/parts/thing.rialto.yaml")
        fragment.writeText(fragment.readText().replace("uuid", "Missing"))
        assertThat(runner("rialtoValidate").buildAndFail().output).contains("error[RIALTO-K004]")
    }

    @Test
    fun `bundles a contract over several files into one document`() {
        setup("valid/fragments/contract.rialto.yaml")
        runner("rialtoBundle").build()
        val bundled = project.resolve("build/rialto/bundled.rialto.yaml").readText()
        assertThat(bundled).contains("rialto:").doesNotContain("include")
    }

    @Test
    fun `updates the sources of a contract without remote sources`() {
        setup("valid/minimal.yaml")
        assertThat(
            runner("rialtoSourcesUpdate").build().output,
        ).contains("0 remote source(s) updated")
    }
}
