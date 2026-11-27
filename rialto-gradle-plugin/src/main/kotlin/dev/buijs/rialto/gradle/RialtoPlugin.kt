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

import dev.buijs.rialto.core.files.ContractFiles
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.file.RegularFileProperty

/** `rialto { contract = file("contract.rialto.yaml") }` */
abstract class RialtoExtension {
    /** The contract: a single file, or the root of a contract over several files (K5). */
    abstract val contract: RegularFileProperty
}

/**
 * Registers `rialtoValidate`, `rialtoBundle` and `rialtoSourcesUpdate` for the contract of the `rialto` extension.
 *
 * The plugin brings the same commands as the command line into a Gradle build, so a project checks its contract
 * with a task of its own build, without installing the command line. The rules all live in rialto-core, which
 * also says which files a result depends on ([ContractFiles.inputs]); the plugin hands those to Gradle, so a
 * task is up to date, or comes from the build cache, until one of them changes.
 */
class RialtoPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        val extension = project.extensions.create("rialto", RialtoExtension::class.java)

        // Every file the result depends on: the contract, its lock, its fragments and its local sources.
        val contractInputs =
            project.files(
                extension.contract.map { contract ->
                    ContractFiles.inputs(contract.asFile.toPath()).map { it.toFile() }
                },
            )

        val update =
            project.tasks.register("rialtoSourcesUpdate", SourcesUpdateTask::class.java) { task ->
                task.group = "rialto"
                task.description =
                    "Fetches the remote sources of the contract again and locks their new versions."
                task.contract.set(extension.contract)
            }

        project.tasks.register("rialtoValidate", ValidateTask::class.java) { task ->
            task.group = "rialto"
            task.description = "Validates the Rialto contract."
            task.offline.set(project.gradle.startParameter.isOffline)
            task.contract.set(extension.contract)
            task.contractInputs.from(contractInputs)
            // It reads the lock of the contract, which rialtoSourcesUpdate writes.
            task.mustRunAfter(update)
        }

        project.tasks.register("rialtoBundle", BundleTask::class.java) { task ->
            task.group = "rialto"
            task.description = "Writes the bundled form of a contract over several files (K5)."
            task.contract.set(extension.contract)
            task.contractInputs.from(contractInputs)
            task.output.set(project.layout.buildDirectory.file("rialto/bundled.rialto.yaml"))
        }
    }
}
