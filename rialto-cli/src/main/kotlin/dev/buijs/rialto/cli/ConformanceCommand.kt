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

package dev.buijs.rialto.cli

import dev.buijs.rialto.conformance.Conformance
import dev.buijs.rialto.conformance.ConformanceResult
import java.io.PrintStream
import java.nio.file.Path

/**
 * `rialto conformance --spec <dir> [--jobs <n>] [--verbose] -- <command>...`: runs a command once per test contract
 * of the specification, and reports per test contract whether it passed.
 *
 * The exit code is `0` when every test contract passed, and `1` when one failed or there are none.
 */
internal fun conformance(
    args: List<String>,
    out: PrintStream,
    err: PrintStream,
): Int {
    val arguments = ConformanceArguments.of(args) ?: return usage(err)
    val results = Conformance.run(arguments.spec, arguments.command, arguments.jobs)
    if (results.isEmpty()) {
        err.println("error: no test contracts in ${arguments.spec.resolve(Conformance.CONTRACTS)}")
        return 1
    }

    results.forEach { printResult(it, arguments.verbose, out) }

    val failed = results.count { !it.passed }

    val passed = results.size - failed
    out.println()
    out.println("$passed passed, $failed failed; command: ${arguments.command.joinToString(" ")}")

    return if (failed == 0) 0 else 1
}

/** One line per test contract: `PASS <name>`, or `FAIL <name>: <why>`, with the output of the run when [verbose]. */
private fun printResult(
    result: ConformanceResult,
    verbose: Boolean,
    out: PrintStream,
) {
    if (result.passed) {
        out.println("PASS ${result.name}")
        return
    }

    out.println("FAIL ${result.name}: ${result.failure}")
    if (verbose && result.output.isNotBlank()) {
        result.output
            .trim()
            .lines()
            .forEach { out.println("     $it") }
    }
}

/** The arguments of `rialto conformance`: the options before `--` and the command after it. */
internal class ConformanceArguments(
    val spec: Path,
    val jobs: Int,
    val verbose: Boolean,
    val command: List<String>,
) {
    companion object {
        /** Reads [args], or returns null on wrong usage: no `--spec`, no command after `--`, or an option it does not know. */
        fun of(args: List<String>): ConformanceArguments? {
            val separator = args.indexOf("--")
            if (separator < 0) {
                return null
            }

            val command = args.drop(separator + 1)
            if (command.isEmpty()) {
                return null
            }

            var spec: String? = null
            var jobs = 1
            var verbose = false
            val options = args.take(separator).iterator()
            while (options.hasNext()) {
                when (options.next()) {
                    "--spec" -> {
                        spec = options.nextOrNull() ?: return null
                    }
                    "--jobs" -> {
                        jobs = positiveNumber(options.nextOrNull()) ?: return null
                    }
                    "--verbose" -> {
                        verbose = true
                    }
                    else -> {
                        return null
                    }
                }
            }

            if (spec == null) {
                return null
            }

            return ConformanceArguments(Path.of(spec), jobs, verbose, command)
        }

        private fun Iterator<String>.nextOrNull(): String? = if (hasNext()) next() else null

        /** [text] as a number of at least 1, or null when it is no such number. */
        private fun positiveNumber(text: String?): Int? {
            val number = text?.toIntOrNull() ?: return null
            if (number < 1) {
                return null
            }

            return number
        }
    }
}
