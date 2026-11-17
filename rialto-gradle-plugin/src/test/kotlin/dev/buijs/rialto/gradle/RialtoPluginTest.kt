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
    private val dir: File get() = temporary.canonicalFile

    private val contracts =
        Path
            .of(
                System.getProperty("rialto.spec"),
            ).resolve("examples/contracts")
            .toFile()

    private fun project(contract: String? = null): Project =
        ProjectBuilder.builder().withProjectDir(dir).build().also { project ->
            project.pluginManager.apply(RialtoPlugin::class.java)
            if (contract != null) {
                project.extensions
                    .getByType(
                        RialtoExtension::class.java,
                    ).contract
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
        val project = project("contract.rialto.yaml")
        val validate = project.task<ValidateTask>("rialtoValidate")
        val bundle = project.task<BundleTask>("rialtoBundle")
        val update = project.task<SourcesUpdateTask>("rialtoSourcesUpdate")

        listOf(validate, bundle, update).forEach {
            assertThat(it.group).isEqualTo("rialto")
            assertThat(it.description).isNotBlank()
        }

        assertThat(validate.offline.get()).isFalse()
        assertThat(validate.mustRunAfter.getDependencies(validate)).contains(update)
        assertThat(
            bundle.output.get().asFile,
        ).isEqualTo(dir.resolve("build/rialto/bundled.rialto.yaml"))
        assertThat(bundle.contractLocation.get()).isEqualTo("../../contract.rialto.yaml")
    }

    @Test
    fun `tracks the contract, its lock, its fragments and its local sources, but no remote one`() {
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
        val inputs =
            project(
                "contract.rialto.yaml",
            ).task<ValidateTask>("rialtoValidate").contractInputs.files
        assertThat(inputs.map { it.relativeTo(dir).invariantSeparatorsPath }).containsExactly(
            "contract.rialto.yaml",
            "rialto.lock",
            "parts/a.yaml",
            "api.json",
            "db/V1__a.sql",
            "db/V2__b.sql",
        )
    }

    @Test
    fun `a contract that does not exist is its only input`() {
        val inputs =
            project(
                "missing.rialto.yaml",
            ).task<BundleTask>("rialtoBundle").contractInputs.files
        assertThat(inputs).containsExactly(dir.resolve("missing.rialto.yaml"))
    }

    @Test
    fun `validate marks a valid contract and fails on an invalid one with the count of errors`() {
        contracts.resolve("valid/minimal.yaml").copyTo(dir.resolve("valid.yaml"))
        val valid = project("valid.yaml").task<ValidateTask>("rialtoValidate")
        // Gradle creates the output directory before the action runs.
        valid.marker
            .get()
            .asFile
            .mkdirs()
        valid.validate()
        assertThat(dir.resolve("build/rialto/validate/valid").readText()).isEqualTo("ok\n")

        contracts.resolve("invalid/unknown-type.yaml").copyTo(dir.resolve("invalid.yaml"))
        val invalid = project("invalid.yaml").task<ValidateTask>("rialtoValidate")
        assertThatThrownBy { invalid.validate() }
            .isInstanceOf(GradleException::class.java)
            .hasMessage("Rialto found 1 error(s); see the messages above.")
    }

    @Test
    fun `validate offline reads remote sources from the cache only`() {
        write(
            "contract.rialto.yaml",
            "rialto: '1.0.0'\ninfo: {name: x, version: 1.0.0}\nsources:\n" +
                "  remote: {kind: openapi, path: 'https://x.example/api.json', version: 1.0.0, status: current}\n",
        )
        System.setProperty("rialto.cache", dir.resolve("cache").path)
        try {
            val task = project("contract.rialto.yaml").task<ValidateTask>("rialtoValidate")
            task.offline.set(true)
            assertThatThrownBy {
                task.validate()
            }.hasMessage("Rialto found 1 error(s); see the messages above.")
            assertThat(dir.resolve("rialto.lock")).doesNotExist()
        } finally {
            System.clearProperty("rialto.cache")
        }
    }

    @Test
    fun `bundle writes the bundled form, and fails on a contract with errors`() {
        contracts.resolve("valid/fragments").copyRecursively(dir.resolve("fragments"))
        project("fragments/contract.rialto.yaml").task<BundleTask>("rialtoBundle").bundle()
        assertThat(
            dir.resolve("build/rialto/bundled.rialto.yaml").readText(),
        ).contains("rialto:").doesNotContain("include")

        write(
            "broken.rialto.yaml",
            "rialto: '1.0.0'\ninfo: {name: x, version: 1.0.0}\ninclude: [none/*.yaml]\n",
        )
        val broken = project("broken.rialto.yaml").task<BundleTask>("rialtoBundle")
        assertThatThrownBy { broken.bundle() }.isInstanceOf(GradleException::class.java)
    }

    @Test
    fun `sources update locks nothing for a contract without remote sources, and fails on one that cannot be read`() {
        contracts.resolve("valid/minimal.yaml").copyTo(dir.resolve("minimal.yaml"))
        project("minimal.yaml").task<SourcesUpdateTask>("rialtoSourcesUpdate").update()
        assertThat(dir.resolve("rialto.lock")).doesNotExist()

        write("list.yaml", "- a\n")
        val unreadable = project("list.yaml").task<SourcesUpdateTask>("rialtoSourcesUpdate")
        assertThatThrownBy { unreadable.update() }.isInstanceOf(GradleException::class.java)
    }

    @Test
    fun `relative paths use slashes and climb out of the directory`() {
        assertThat(relativePath(File("/a/b"), File("/a/b/c/d.yaml"))).isEqualTo("c/d.yaml")
        assertThat(
            relativePath(File("/a/b/build/rialto"), File("/a/b/c.yaml")),
        ).isEqualTo("../../c.yaml")
    }
}
