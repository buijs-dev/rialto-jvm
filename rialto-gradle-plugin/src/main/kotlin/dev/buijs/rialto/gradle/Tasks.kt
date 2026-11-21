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
import dev.buijs.rialto.contract.Outcome
import dev.buijs.rialto.contract.RemoteMode
import dev.buijs.rialto.contract.RemoteSources
import dev.buijs.rialto.contract.Severity
import dev.buijs.rialto.contract.SourceChange
import dev.buijs.rialto.contract.relativePath
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.logging.LogLevel
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
import org.gradle.work.DisableCachingByDefault

/** Fetches every remote source of the contract again and locks its new hash (`rialtoSourcesUpdate`, K4.2). */
@DisableCachingByDefault(
    because = "It fetches remote documents and writes rialto.lock.",
)
abstract class SourcesUpdateTask : DefaultTask() {
    @get:Internal
    abstract val contract: RegularFileProperty

    @TaskAction
    fun update() {
        val contract = contract.get().asFile.toPath()
        val outcome = RemoteSources.update(contract)
        log(this, outcome.diagnostics)

        when (outcome) {
            is Outcome.Nok -> {
                throw failure(outcome)
            }
            is Outcome.Ok -> {
                outcome.value.forEach { logger.lifecycle(it.toString()) }

                logger.lifecycle(SourceChange.summary(outcome.value))
            }
        }
    }
}

/**
 * Validates the contract (`rialtoValidate`), as `rialto validate` does.
 *
 * A validation has no real output, so the task writes a marker file in [marker] when it succeeds: that gives
 * Gradle something to keep up to date and to cache, and an unchanged contract is not validated again.
 */
@CacheableTask
abstract class ValidateTask : DefaultTask() {
    /** Remote sources from the cache only, as with Gradle's `--offline` (K4.3). */
    @get:Internal
    abstract val offline: Property<Boolean>

    @get:Internal
    abstract val contract: RegularFileProperty

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val contractInputs: ConfigurableFileCollection

    @get:OutputDirectory
    val marker = project.layout.buildDirectory.dir("rialto/validate")

    private val remote: RemoteMode get() =
        if (offline.getOrElse(false)) {
            RemoteMode.OFFLINE
        } else {
            RemoteMode.LOCKED
        }

    @TaskAction
    fun validate() {
        val contract = contract.get().asFile.toPath()
        val outcome = ContractValidator.validate(contract, remote)
        log(this, outcome.diagnostics)
        if (outcome is Outcome.Nok) {
            throw failure(outcome)
        }

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
     * Where [contract] is stored seen from the directory of [output]. The bundle writes the source paths
     * relative to [output], so the layout belongs in the cache key; [contractInputs] only covers
     * the names of the files.
     */
    @get:Input
    val contractLocation: Provider<String>
        get() =
            contract.zip(output.locationOnly) { contract, output ->
                relativePath(output.asFile.parentFile.toPath(), contract.asFile.toPath())
            }

    @TaskAction
    fun bundle() {
        val contract = contract.get().asFile.toPath()
        val output = output.get().asFile.toPath()
        val outcome = ContractFiles.bundle(contract, output)
        log(this, outcome.diagnostics)
        if (outcome is Outcome.Nok) {
            throw failure(outcome)
        }
    }
}

/** Logs [diagnostics] on [task], each at the level of its severity. */
internal fun log(
    task: DefaultTask,
    diagnostics: List<Diagnostic>,
) {
    diagnostics.forEach {
        task.logger.log(it.severity.toLogLevel(), it.toString())
    }
}

/** The exception that fails the build for [outcome]; its findings were logged before. Warnings never fail a build. */
internal fun failure(outcome: Outcome.Nok): GradleException {
    val errors =
        outcome.diagnostics.count {
            it.severity == Severity.ERROR
        }

    return GradleException("Rialto found $errors error(s); see the messages above.")
}

private fun Severity.toLogLevel() =
    when (this) {
        Severity.ERROR -> LogLevel.ERROR
        Severity.WARNING -> LogLevel.WARN
    }
