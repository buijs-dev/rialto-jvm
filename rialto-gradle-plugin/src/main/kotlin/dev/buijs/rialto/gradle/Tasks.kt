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

import dev.buijs.rialto.contract.ContractFiles
import dev.buijs.rialto.contract.ContractValidator
import dev.buijs.rialto.contract.Diagnostic
import dev.buijs.rialto.contract.Diagnostics
import dev.buijs.rialto.contract.RemoteMode
import dev.buijs.rialto.contract.RemoteSources
import dev.buijs.rialto.contract.Severity
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import java.io.File

/** The path of [file] relative to [directory], with `/` as separator. */
internal fun relativePath(
    directory: File,
    file: File,
): String =
    directory.absoluteFile
        .toPath()
        .normalize()
        .relativize(file.absoluteFile.toPath().normalize())
        .joinToString("/")

private fun report(
    task: DefaultTask,
    diagnostics: List<Diagnostic>,
) {
    diagnostics.forEach {
        if (it.severity == Severity.ERROR) {
            task.logger.error(it.toString())
        } else {
            task.logger.warn(it.toString())
        }
    }

    val errors = diagnostics.count { it.severity == Severity.ERROR }

    if (errors > 0) throw GradleException("Rialto found $errors error(s); see the messages above.")
}

/** Fetches every remote source of the contract again and locks its new hash (`rialtoSourcesUpdate`, K4.2). */
@org.gradle.work.DisableCachingByDefault(
    because = "It fetches remote documents and writes rialto.lock.",
)
abstract class SourcesUpdateTask : DefaultTask() {
    @get:Internal
    abstract val contract: RegularFileProperty

    @TaskAction
    fun update() {
        val diagnostics = Diagnostics()
        val changes = RemoteSources.update(contract.get().asFile.toPath(), diagnostics)
        changes.forEach { logger.lifecycle("${it.url}: ${it.before ?: "new"} -> ${it.after}") }

        report(this, diagnostics.all)
        logger.lifecycle("${changes.size} remote source(s) updated in ${RemoteSources.LOCK_FILE}")
    }
}

/** Validates the contract (`rialtoValidate`). */
@CacheableTask
abstract class ValidateTask : DefaultTask() {
    /** Remote sources from the cache only, as with Gradle's `--offline` (K4.3). */
    @get:Internal
    abstract val offline: Property<Boolean>

    private val remote: RemoteMode get() =
        if (offline.getOrElse(false)) {
            RemoteMode.OFFLINE
        } else {
            RemoteMode.LOCKED
        }

    @get:Internal
    abstract val contract: RegularFileProperty

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val contractInputs: ConfigurableFileCollection

    @get:OutputDirectory
    val marker = project.layout.buildDirectory.dir("rialto/validate")

    @TaskAction
    fun validate() {
        report(this, ContractValidator.validate(contract.get().asFile.toPath(), remote).diagnostics)
        marker
            .get()
            .asFile
            .resolve("valid")
            .writeText("ok\n")
    }
}

/** Writes the bundled form of the contract (`rialtoBundle`): one document, also for a contract over several files (K5). */
@CacheableTask
abstract class BundleTask : DefaultTask() {
    @get:Internal
    abstract val contract: RegularFileProperty

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val contractInputs: ConfigurableFileCollection

    @get:OutputFile
    abstract val output: RegularFileProperty

    /**
     * Where [contract] lies seen from the directory of [output]. The bundle writes the source paths
     * relative to [output], so the layout belongs in the cache key; [contractInputs] only covers
     * the names of the files.
     */
    @get:Input
    val contractLocation: Provider<String>
        get() =
            contract.zip(output.locationOnly) { contract, output ->
                relativePath(output.asFile.parentFile, contract.asFile)
            }

    @TaskAction
    fun bundle() {
        val diagnostics = Diagnostics()
        ContractFiles.bundle(
            contract.get().asFile.toPath(),
            output.get().asFile.toPath(),
            diagnostics,
        )
        report(this, diagnostics.all)
    }
}
