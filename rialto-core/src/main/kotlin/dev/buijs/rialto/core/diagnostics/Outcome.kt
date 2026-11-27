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

package dev.buijs.rialto.core.diagnostics

/**
 * What a step returns: its value when it succeeded, and every finding either way.
 *
 * A function that can find mistakes returns them here, so they are part of its signature. The value is only
 * reachable through [Ok], and a `when` over the outcome is the way to it: a caller cannot go on with the value of
 * a step that failed, nor overlook that it failed.
 *
 * ```
 * val document =
 *     when (val read = ContractFiles.read(file)) {
 *         is Outcome.Nok -> return read
 *         is Outcome.Ok -> read.value
 *     }
 * ```
 */
sealed class Outcome<out T> {
    /** Every finding of the step, in the order it made them. */
    abstract val diagnostics: List<Diagnostic>

    /** The step succeeded: [value] can be used. [diagnostics] holds warnings only. */
    data class Ok<T>(
        val value: T,
        override val diagnostics: List<Diagnostic> = emptyList(),
    ) : Outcome<T>() {
        init {
            require(!diagnostics.hasErrors()) { "An outcome with an error is not Ok" }
        }
    }

    /** The step failed: there is no value. [diagnostics] holds at least one error. */
    data class Nok(
        override val diagnostics: List<Diagnostic>,
    ) : Outcome<Nothing>() {
        init {
            require(diagnostics.hasErrors()) { "An outcome without an error is not Nok" }
        }
    }

    companion object {
        /** [Ok] with [value] when none of [diagnostics] is an error, otherwise [Nok]. */
        fun <T> of(
            value: T,
            diagnostics: List<Diagnostic>,
        ): Outcome<T> =
            if (diagnostics.hasErrors()) {
                Nok(diagnostics)
            } else {
                Ok(value, diagnostics)
            }
    }
}
