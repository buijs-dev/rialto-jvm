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

import java.nio.file.Path

/** Whether a finding fails the run: an [ERROR] does, a [WARNING] is reported but the contract stays valid. */
enum class Severity(
    /** As reported, in text and in `--format json`. */
    val label: String,
) {
    ERROR("error"),
    WARNING("warning"),
}

/** Whether one of these findings is an error. */
fun Iterable<Diagnostic>.hasErrors(): Boolean = any { it.severity == Severity.ERROR }

/**
 * Where a finding was made: a file, a position in it when known, and a JSON Pointer. The position is for a person
 * (`file:line:column`), the pointer for tools, which read it from `--format json`.
 */
data class Location(
    val file: Path,
    val line: Int? = null,
    val column: Int? = null,
    val pointer: String = "",
) {
    override fun toString(): String {
        val position = if (line != null) ":$line:${column ?: 1}" else ""
        val path = if (pointer.isEmpty()) "" else " #$pointer"
        return "${file.fileName}$position$path"
    }
}

/**
 * One finding: what is wrong ([code], [message]), where ([location]), and optionally how to fix it ([hint]).
 * [toString] is the text form the command line and the Gradle plugin print.
 */
data class Diagnostic(
    val severity: Severity,
    val code: Code,
    val message: String,
    val location: Location,
    val hint: String? = null,
    /** The requirement this finding enforces, when it differs from the first one of [code]. */
    val requirementOverride: String? = null,
) {
    /** The requirement this finding enforces: its own, else the one of [code]. */
    val requirement: String
        get() = requirementOverride ?: code.requirement

    /** The code as reported. */
    val id: String
        get() = code.id

    override fun toString(): String {
        val hintLine = hint?.let { "\n  hint: $it" } ?: ""
        return "${severity.label}[$id] $location: $message ($requirement)$hintLine"
    }
}

/**
 * Collects findings inside one step. A step that makes many findings in a deep call tree, such as a set of checks,
 * reports here instead of handing lists up; one run then shows the author all mistakes it can find at once.
 *
 * It is internal on purpose: whoever creates a collector also reads it, and hands the findings on in an [Outcome]
 * or as a list. It is never a parameter of a public function, because a caller that does not look at it would
 * miss the errors.
 */
internal class Diagnostics {
    private val items = mutableListOf<Diagnostic>()

    val all: List<Diagnostic> get() = items.toList()

    val hasErrors: Boolean get() = items.hasErrors()

    fun add(diagnostic: Diagnostic) {
        items += diagnostic
    }

    fun error(
        code: Code,
        message: String,
        location: Location,
        hint: String? = null,
        requirement: String? = null,
    ) {
        items += Diagnostic(Severity.ERROR, code, message, location, hint, requirement)
    }

    fun warning(
        code: Code,
        message: String,
        location: Location,
        hint: String? = null,
    ) {
        items += Diagnostic(Severity.WARNING, code, message, location, hint)
    }
}
