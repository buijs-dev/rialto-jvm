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

package dev.buijs.rialto.core.files

import dev.buijs.rialto.core.document.ContractMapper
import dev.buijs.rialto.core.model.Contract
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

/**
 * Writes a [Contract] as a YAML document of the contract format: the way back from the model to a file.
 *
 * Validation reads documents into the model; this is its counterpart, for code that builds or changes a
 * contract in memory and must hand it on as a document. Reading what it writes gives the same model again,
 * top-level extension keys included; extension keys deeper in the document are not part of the model.
 */
object ContractWriter {
    /** The YAML text of [contract], in declaration order, without absent and empty values. */
    fun write(contract: Contract): String = ContractMapper.yaml.writeValueAsString(contract)

    /** Writes [contract] to [file], creating its directory when needed. */
    fun write(
        contract: Contract,
        file: Path,
    ) {
        file.toAbsolutePath().parent.createDirectories()
        file.writeText(write(contract))
    }
}
