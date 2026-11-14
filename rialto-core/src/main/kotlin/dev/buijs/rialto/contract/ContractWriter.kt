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

package dev.buijs.rialto.contract

import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

/** Writes a [Contract] as a YAML document of the contract format. */
object ContractWriter {
    fun write(contract: Contract): String = ContractMapper.yaml.writeValueAsString(contract)

    fun write(
        contract: Contract,
        file: Path,
    ) {
        file.toAbsolutePath().parent.createDirectories()
        file.writeText(write(contract))
    }
}
