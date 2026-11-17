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

/** What a set of checks reads, and where it reports: a contract that is valid against the JSON Schema. */
internal class CheckContext(
    val contract: Contract,
    val document: LocatedDocument,
    val sources: Sources,
    val diagnostics: Diagnostics,
) {
    /** A JSON Pointer from its segments, each escaped. */
    fun pointer(vararg segments: Any): String =
        segments.joinToString("") { "/" + LocatedDocument.segment(it.toString()) }

    fun error(
        code: Code,
        message: String,
        pointer: String,
        hint: String? = null,
        requirement: String? = null,
    ) = diagnostics.error(code, message, document.locate(pointer), hint, requirement)
}

/**
 * One set of checks over the contract model, run after the JSON Schema: the format's own (references and names,
 * K1.2, K1.3, K2.1), or the rules of one profile. Each profile adds its set to [ContractValidator.checks].
 */
internal fun interface ContractChecks {
    fun run(context: CheckContext)
}
