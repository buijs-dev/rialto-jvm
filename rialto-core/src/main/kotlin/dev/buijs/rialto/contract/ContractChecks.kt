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

/**
 * What a set of checks reads, and where it reports: a contract that is valid against the JSON Schema.
 *
 * It bundles the three views of one contract a rule needs: the typed [contract] to reason about, the
 * [document] to point a finding at the right file and line, and the loaded [sources] to follow references into
 * OpenAPI, AsyncAPI and SQL. Checks only ever report through it, so every finding gets a position.
 *
 * The context collects the findings itself; whoever runs the checks reads them from [diagnostics] afterward.
 */
internal class CheckContext(
    val contract: Contract,
    val document: LocatedDocument,
    val sources: Sources,
) {
    private val collected = Diagnostics()

    /** Every finding the checks reported so far, in order. */
    val diagnostics: List<Diagnostic>
        get() = collected.all

    /** A JSON Pointer from its segments, each escaped. */
    fun pointer(vararg segments: Any): String =
        segments.joinToString("") { "/" + LocatedDocument.segment(it.toString()) }

    /** Reports an error at the element [pointer] refers to, located in the file and line it was read from. */
    fun error(
        code: Code,
        message: String,
        pointer: String,
        hint: String? = null,
        requirement: String? = null,
    ) = collected.error(code, message, document.locate(pointer), hint, requirement)
}

/**
 * One set of checks over the contract model, run after the JSON Schema: the format's own (references and names,
 * K1.2, K1.3, K2.1), or the rules of one profile. Each profile adds its set to [ContractValidator.checks].
 *
 * This keeps the rules of the specification apart from the plumbing of reading and reporting: a set of checks
 * only looks at the model and reports findings; it does not read files or decide whether the run fails.
 */
internal fun interface ContractChecks {
    /** Checks the contract of [context] and reports every finding there; it never throws for an invalid contract. */
    fun run(context: CheckContext)
}
