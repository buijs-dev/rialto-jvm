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

import dev.buijs.rialto.core.diagnostics.Code
import dev.buijs.rialto.core.diagnostics.Diagnostic
import dev.buijs.rialto.core.diagnostics.Location
import dev.buijs.rialto.core.diagnostics.Outcome
import dev.buijs.rialto.core.diagnostics.Severity
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path

/** The plugin in the project model: its tasks, their inputs, and their actions run in this JVM. */
class RialtoPluginTest {
    @TempDir
    lateinit var temporary: File

    /** The project directory, with symbolic links resolved as Gradle resolves them. */
    private val dir: File
        get() = temporary.canonicalFile

    /** The test contracts of this module, copies of a few of the specification's (see [testContracts]). */
    private val contracts = testContracts()

    private fun project(contract: String? = null): Project =
        ProjectBuilder.builder().withProjectDir(dir).build().also { project ->
            project.pluginManager.apply(RialtoPlugin::class.java)
            if (contract != null) {
                project.extensions
                    .getByType(RialtoExtension::class.java)
                    .contract
                    .set(project.file(contract))
            }
        }

    private fun write(
        name: String,
        text: String,
    ) = dir.resolve(name).also { it.parentFile.mkdirs() }.writeText(text)

    private inline fun <reified T> Project.task(name: String): T = tasks.getByName(name) as T

    @Test
    fun `registers validate, bundle and sources update in the rialto group`() {
        // when
        val project = project("contract.rialto.yaml")

        // then
        val validate = project.task<ValidateTask>("rialtoValidate")
        val bundle = project.task<BundleTask>("rialtoBundle")
        val update = project.task<SourcesUpdateTask>("rialtoSourcesUpdate")
        assertThat(update.group).isEqualTo("rialto")
        assertThat(update.description).isNotBlank()
        assertThat(validate.group).isEqualTo("rialto")
        assertThat(validate.description).isNotBlank()
        assertThat(validate.offline.get()).isFalse()
        assertThat(validate.mustRunAfter.getDependencies(validate)).contains(update)
        assertThat(bundle.group).isEqualTo("rialto")
        assertThat(bundle.description).isNotBlank()
        assertThat(bundle.output.get().asFile).isEqualTo(dir.resolve("build/rialto/bundled.rialto.yaml"))
        assertThat(bundle.contractLocation.get()).isEqualTo("../../contract.rialto.yaml")
    }

    @Test
    fun `tracks the contract, its lock, its fragments and its local sources, but no remote one`() {
        // given
        write(
            "contract.rialto.yaml",
            "rialto: '1.0.0'\ninfo: {name: x, version: 1.0.0}\n" +
                "include: [parts/*.yaml]\nsources:\n" +
                "  api: {kind: openapi, path: api.json, version: 1.0.0, status: current}\n" +
                "  db: {kind: sql, path: 'db/*.sql', dialect: postgresql}\n" +
                "  remote: {kind: openapi, path: 'https://x.example/api.json', version: 1.0.0, status: current}\n",
        )
        write("parts/a.yaml", "rialto: '1.0.0'\n")
        write("db/V2__b.sql", "")
        write("db/V1__a.sql", "")

        // when
        val inputs = project("contract.rialto.yaml").task<ValidateTask>("rialtoValidate").contractInputs.files

        // then
        assertThat(inputs.map { it.relativeTo(dir).invariantSeparatorsPath }).containsExactly(
            "contract.rialto.yaml",
            "contract.rialto.lock",
            "parts/a.yaml",
            "api.json",
            "db/V1__a.sql",
            "db/V2__b.sql",
        )
    }

    @Test
    fun `a contract that does not exist is its only input`() {
        // when
        val inputs = project("missing.rialto.yaml").task<BundleTask>("rialtoBundle").contractInputs.files

        // then
        assertThat(inputs).containsExactly(dir.resolve("missing.rialto.yaml"))
    }

    @Test
    fun `a contract that is no valid YAML is an input with its lock, and validate reports it`() {
        // given
        write("broken.rialto.yaml", "rialto: [unclosed\n")
        val project = project("broken.rialto.yaml")

        // when
        val inputs = project.task<BundleTask>("rialtoBundle").contractInputs.files

        // then
        assertThat(inputs).containsExactly(dir.resolve("broken.rialto.yaml"), dir.resolve("broken.rialto.lock"))

        // given
        val validate = project.task<ValidateTask>("rialtoValidate")

        // expect
        assertThatThrownBy { validate.validate() }.isInstanceOf(GradleException::class.java)
    }

    @Test
    fun `validate marks a valid contract and fails on an invalid one with the count of errors`() {
        // given
        contracts.resolve("valid/minimal.yaml").copyTo(dir.resolve("valid.yaml"))
        val valid = project("valid.yaml").task<ValidateTask>("rialtoValidate")
        // Gradle creates the output directory before the action runs.
        valid.marker
            .get()
            .asFile
            .mkdirs()

        // when
        valid.validate()

        // then
        assertThat(dir.resolve("build/rialto/validate/valid").readText()).isEqualTo("ok\n")

        // given
        contracts.resolve("invalid/unknown-type.yaml").copyTo(dir.resolve("invalid.yaml"))
        val invalid = project("invalid.yaml").task<ValidateTask>("rialtoValidate")

        // expect
        assertThatThrownBy { invalid.validate() }
            .isInstanceOf(GradleException::class.java)
            .hasMessage("Rialto found 1 error(s); see the messages above.")
    }

    @Test
    fun `validate offline reads remote sources from the cache only`() {
        // given
        write(
            "contract.rialto.yaml",
            "rialto: '1.0.0'\ninfo: {name: x, version: 1.0.0}\nsources:\n" +
                "  remote: {kind: openapi, path: 'https://x.example/api.json', version: 1.0.0, status: current}\n",
        )
        System.setProperty("rialto.cache", dir.resolve("cache").path)
        try {
            val task = project("contract.rialto.yaml").task<ValidateTask>("rialtoValidate")
            task.offline.set(true)

            // expect
            assertThatThrownBy { task.validate() }.hasMessage("Rialto found 1 error(s); see the messages above.")
            assertThat(dir.resolve("contract.rialto.lock")).doesNotExist()
        } finally {
            System.clearProperty("rialto.cache")
        }
    }

    @Test
    fun `bundle writes the bundled form, and fails on a contract with errors`() {
        // given
        contracts.resolve("valid/fragments").copyRecursively(dir.resolve("fragments"))

        // when
        project("fragments/contract.rialto.yaml").task<BundleTask>("rialtoBundle").bundle()

        // then
        val bundled = dir.resolve("build/rialto/bundled.rialto.yaml").readText()
        assertThat(bundled).contains("rialto:").doesNotContain("include")

        // given
        write("broken.rialto.yaml", "rialto: '1.0.0'\ninfo: {name: x, version: 1.0.0}\ninclude: [none/*.yaml]\n")
        val broken = project("broken.rialto.yaml").task<BundleTask>("rialtoBundle")

        // expect
        assertThatThrownBy { broken.bundle() }.isInstanceOf(GradleException::class.java)
    }

    @Test
    fun `sources update locks nothing for a contract without remote sources, and fails on one that cannot be read`() {
        // given
        contracts.resolve("valid/minimal.yaml").copyTo(dir.resolve("minimal.yaml"))

        // when
        project("minimal.yaml").task<SourcesUpdateTask>("rialtoSourcesUpdate").update()

        // then
        assertThat(dir.resolve("minimal.rialto.lock")).doesNotExist()

        // given
        write("list.yaml", "- a\n")
        val unreadable = project("list.yaml").task<SourcesUpdateTask>("rialtoSourcesUpdate")

        // expect
        assertThatThrownBy { unreadable.update() }.isInstanceOf(GradleException::class.java)
    }

    @Test
    fun `a task logs warnings and fails only on errors, with their count`() {
        // given
        val task = project().tasks.register("probe", org.gradle.api.DefaultTask::class.java).get()
        val at = Location(Path.of("a.yaml"))
        val warning = Diagnostic(Severity.WARNING, Code.SCHEMA, "careful", at)
        val error = Diagnostic(Severity.ERROR, Code.SCHEMA, "wrong", at)

        // when
        log(task, listOf(warning, error))

        // then
        assertThat(failure(Outcome.Nok(listOf(warning, error, error))))
            .isInstanceOf(GradleException::class.java)
            .hasMessage("Rialto found 2 error(s); see the messages above.")
    }
}
