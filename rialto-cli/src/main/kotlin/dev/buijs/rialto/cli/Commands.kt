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

import dev.buijs.rialto.core.conformance.Conformance
import dev.buijs.rialto.core.diagnostics.Diagnostic
import dev.buijs.rialto.core.diagnostics.Outcome
import dev.buijs.rialto.core.diagnostics.Severity
import dev.buijs.rialto.core.document.ContractMapper
import dev.buijs.rialto.core.files.ContractFiles
import dev.buijs.rialto.core.sources.RemoteMode
import dev.buijs.rialto.core.sources.RemoteSources
import dev.buijs.rialto.core.sources.SourceChange
import dev.buijs.rialto.core.validation.ContractValidator
import dev.buijs.rialto.core.validation.ValidContract
import tools.jackson.databind.node.ObjectNode
import java.io.IOException
import java.io.PrintStream
import java.nio.file.Path
import kotlin.io.path.exists

private const val USAGE = """Usage:
  rialto validate [--format json] <contract>...
  rialto bundle --output <file> <contract>
  rialto sources update <contract>
  rialto conformance --spec <dir> [--jobs <n>] [--verbose] -- <command>...

--offline (validate): remote sources from the cache only.
--format json (validate): one JSON document on stdout instead of text (docs/diagnostics.md).

conformance runs <command> once per test contract of the specification in <dir>, a checkout of rialto-spec or its
unpacked artifact. {contract} in the command is the path of the test contract; without it the path comes last.
--jobs <n>: <n> test contracts at a time. --verbose: the output of each failing run.

Exit code 0 on success, 1 when a contract has errors or a file cannot be written, 64 on wrong usage,
70 when rialto itself fails."""

/** The exit code when the tool itself fails (`EX_SOFTWARE`): never the fault of the contract. */
private const val EXIT_UNEXPECTED = 70

/**
 * Runs the command line and returns the exit code: `0` on success, `1` when a contract has errors or a file cannot
 * be written, `64` on wrong usage, and `70` when the tool itself fails.
 *
 * The command line is a thin layer over rialto-core: each command parses its arguments, calls one function of
 * the library ([ContractValidator.validate], [ContractFiles.bundle], [RemoteSources.update], [Conformance.run]) and
 * prints the findings, errors to [err] and the rest to [out]. It holds no rules of its own, so it gives the same verdict as
 * the Gradle plugin. Taking the streams as arguments, instead of exiting, lets the tests run it in process.
 *
 * An exception no command expects is a bug in the tool. It is reported as such, with its stack trace and its own
 * exit code, so a script never takes it for a contract with errors.
 */
fun run(
    args: List<String>,
    out: PrintStream,
    err: PrintStream,
): Int {
    val arguments = args.drop(1)

    return try {
        when (args.firstOrNull()) {
            "validate" -> {
                validate(arguments, out, err)
            }
            "bundle" -> {
                bundle(arguments, out, err)
            }
            "sources" -> {
                sources(arguments, out, err)
            }
            "conformance" -> {
                conformance(arguments, out, err)
            }
            else -> {
                usage(err)
            }
        }
    } catch (e: Exception) {
        err.println("error: rialto failed unexpectedly. This is a bug in rialto; please report it:")
        e.printStackTrace(err)
        EXIT_UNEXPECTED
    }
}

/**
 * `rialto validate`, as text or, with `--format json`, as one JSON document. Both forms validate the same way;
 * they differ only in how they show the results. With `--offline`, remote sources come from the cache only.
 */
private fun validate(
    args: List<String>,
    out: PrintStream,
    err: PrintStream,
): Int {
    val remote = if ("--offline" in args) RemoteMode.OFFLINE else RemoteMode.LOCKED
    val format = Format.of(args.filter { it != "--offline" }) ?: return usage(err)
    if (format.rest.isEmpty()) {
        return usage(err)
    }

    val results =
        format.rest.map(Path::of).map { file ->
            Validated(
                file,
                file.takeIf { it.exists() }?.let { ContractValidator.validate(it, remote) },
            )
        }

    if (format.json) {
        printJson(results, out)
    } else {
        printText(results, out, err)
    }

    return if (results.all { it.valid }) 0 else 1
}

/** The outcome of validating one contract, or null when [file] does not exist. */
private data class Validated(
    val file: Path,
    val result: Outcome<ValidContract>?,
) {
    val valid: Boolean get() = result is Outcome.Ok
}

/** `--format text` (the default) or `--format json`, and the arguments without it; null on a wrong value. */
private class Format(
    val json: Boolean,
    val rest: List<String>,
) {
    companion object {
        fun of(args: List<String>): Format? {
            val index = args.indexOf("--format")
            if (index < 0) {
                return Format(false, args)
            }

            val value = args.getOrNull(index + 1) ?: return null
            if (value != "json" && value != "text") {
                return null
            }

            return Format(value == "json", args.take(index) + args.drop(index + 2))
        }
    }
}

/** The findings of each contract, then `<file>: valid` for each valid one. */
private fun printText(
    results: List<Validated>,
    out: PrintStream,
    err: PrintStream,
) = results.forEach { (file, result) ->
    if (result == null) {
        err.println("error: $file not found")
    } else {
        printDiagnostics(result.diagnostics, out, err)
        if (result is Outcome.Ok) {
            out.println("$file: valid")
        }
    }
}

/**
 * `rialto validate --format json`: one JSON document on stdout, for tools such as editors and CI annotations.
 * Its form is described in docs/diagnostics.md; the specification lists it as informative.
 */
private fun printJson(
    results: List<Validated>,
    out: PrintStream,
) {
    val root = ContractMapper.json.createObjectNode()
    val contracts = root.putArray("contracts")
    results.forEach { validated ->
        val (file, result) = validated
        val entry = contracts.addObject().put("file", file.toString())
        entry.put("valid", validated.valid)
        val diagnostics = entry.putArray("diagnostics")
        if (result == null) {
            diagnostics
                .addObject()
                .put("severity", Severity.ERROR.label)
                .putNull("code")
                .put("message", "$file not found")
        } else {
            result.diagnostics.forEach { diagnostics.add(json(it)) }
        }
    }

    root.put("valid", results.all { it.valid })
    out.println(ContractMapper.json.writerWithDefaultPrettyPrinter().writeValueAsString(root))
}

private fun json(diagnostic: Diagnostic): ObjectNode {
    val node = ContractMapper.json.createObjectNode()
    node.put("severity", diagnostic.severity.label)
    node.put("code", diagnostic.id)
    node.put("requirement", diagnostic.requirement)
    node.put("file", diagnostic.location.file.toString())
    node.put("line", diagnostic.location.line)
    node.put("column", diagnostic.location.column)
    node.put("pointer", diagnostic.location.pointer)
    node.put("message", diagnostic.message)
    node.put("hint", diagnostic.hint)
    return node
}

/** Writes the bundled form of a contract over several files (K5). */
private fun bundle(
    args: List<String>,
    out: PrintStream,
    err: PrintStream,
): Int {
    val index = args.indexOf("--output")
    if (index < 0 || index + 1 >= args.size) {
        return usage(err)
    }

    val output = Path.of(args[index + 1])
    val contract = (args.take(index) + args.drop(index + 2)).singleOrNull() ?: return usage(err)
    val outcome =
        try {
            ContractFiles.bundle(Path.of(contract), output)
        } catch (e: IOException) {
            err.println("error: cannot write $output: $e")
            return 1
        }

    printDiagnostics(outcome.diagnostics, out, err)

    return when (outcome) {
        is Outcome.Nok -> {
            1
        }
        is Outcome.Ok -> {
            out.println("$contract: bundled into $output")
            0
        }
    }
}

/** `rialto sources update <contract>`: fetches the remote sources again and pins their new hashes. */
private fun sources(
    args: List<String>,
    out: PrintStream,
    err: PrintStream,
): Int {
    if (args.size != 2 || args[0] != "update") {
        return usage(err)
    }

    val outcome = RemoteSources.update(Path.of(args[1]))
    printDiagnostics(outcome.diagnostics, out, err)

    return when (outcome) {
        is Outcome.Nok -> {
            1
        }
        is Outcome.Ok -> {
            outcome.value.forEach { out.println(it) }

            out.println(SourceChange.summary(outcome.value))
            0
        }
    }
}

internal fun usage(err: PrintStream): Int {
    err.println(USAGE)
    return 64
}

/** Prints [diagnostics]: errors to [err], warnings to [out]. */
internal fun printDiagnostics(
    diagnostics: List<Diagnostic>,
    out: PrintStream,
    err: PrintStream,
) = diagnostics.forEach { (if (it.severity == Severity.ERROR) err else out).println(it) }
