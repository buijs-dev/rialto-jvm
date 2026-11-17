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

import com.fasterxml.jackson.databind.node.ObjectNode
import dev.buijs.rialto.contract.ContractFiles
import dev.buijs.rialto.contract.ContractMapper
import dev.buijs.rialto.contract.ContractValidator
import dev.buijs.rialto.contract.Diagnostic
import dev.buijs.rialto.contract.Diagnostics
import dev.buijs.rialto.contract.RemoteMode
import dev.buijs.rialto.contract.RemoteSources
import dev.buijs.rialto.contract.Severity
import java.io.PrintStream
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.system.exitProcess

private const val USAGE = """Usage:
  rialto validate [--format json] <contract>...
  rialto bundle --output <file> <contract>
  rialto sources update <contract>

--offline (validate): remote sources from the cache only.
--format json (validate): one JSON document on stdout instead of text (docs/diagnostics.md).

Exit code 0 on success, 1 when a contract has errors, 64 on wrong usage."""

fun main(args: Array<String>) {
    exitProcess(run(args.toList(), System.out, System.err))
}

/** Runs the command line and returns the exit code. */
fun run(
    args: List<String>,
    out: PrintStream,
    err: PrintStream,
): Int {
    val remote = if ("--offline" in args) RemoteMode.OFFLINE else RemoteMode.LOCKED
    val rest = args.filter { it != "--offline" }

    return when (rest.firstOrNull()) {
        "validate" -> {
            validateCommand(rest.drop(1), remote, out, err)
        }
        "bundle" -> {
            bundle(rest.drop(1), out, err)
        }
        "sources" -> {
            sources(rest.drop(1), out, err)
        }
        else -> {
            usage(err)
        }
    }
}

private fun sources(
    args: List<String>,
    out: PrintStream,
    err: PrintStream,
): Int {
    if (args.size != 2 || args[0] != "update") return usage(err)
    val diagnostics = Diagnostics()
    val changes = RemoteSources.update(Path.of(args[1]), diagnostics)
    print(diagnostics.all, out, err)
    changes.forEach { out.println("${it.url}: ${it.before ?: "new"} -> ${it.after}") }

    if (!diagnostics.hasErrors) {
        out.println(
            "${changes.size} remote source(s) updated in ${RemoteSources.LOCK_FILE}",
        )
    }

    return if (diagnostics.hasErrors) 1 else 0
}

/** Writes the bundled form of a contract over several files (K5). */
private fun bundle(
    args: List<String>,
    out: PrintStream,
    err: PrintStream,
): Int {
    val index = args.indexOf("--output")
    if (index < 0 || index + 1 >= args.size) return usage(err)
    val output = Path.of(args[index + 1])
    val contract = (args.take(index) + args.drop(index + 2)).singleOrNull() ?: return usage(err)
    val diagnostics = Diagnostics()
    val written = ContractFiles.bundle(Path.of(contract), output, diagnostics)
    print(diagnostics.all, out, err)
    if (written) out.println("$contract: bundled into $output")
    return if (written) 0 else 1
}

private fun usage(err: PrintStream): Int {
    err.println(USAGE)
    return 64
}

private fun print(
    diagnostics: List<Diagnostic>,
    out: PrintStream,
    err: PrintStream,
) = diagnostics.forEach { (if (it.severity == Severity.ERROR) err else out).println(it) }

private fun validate(
    files: List<String>,
    remote: RemoteMode,
    out: PrintStream,
    err: PrintStream,
): Int {
    if (files.isEmpty()) return usage(err)
    var failed = false
    files.map(Path::of).forEach { file ->
        if (!file.exists()) {
            err.println("error: $file not found")
            failed = true
            return@forEach
        }

        val result = ContractValidator.validate(file, remote)
        print(result.diagnostics, out, err)
        if (result.valid) out.println("$file: valid") else failed = true
    }

    return if (failed) 1 else 0
}

/** `rialto validate`, as text or, with `--format json`, as one JSON document. */
private fun validateCommand(
    args: List<String>,
    remote: RemoteMode,
    out: PrintStream,
    err: PrintStream,
): Int {
    val format = Format.of(args) ?: return usage(err)
    return if (format.json) {
        validateJson(format.rest, remote, out, err)
    } else {
        validate(format.rest, remote, out, err)
    }
}

/** `--format text` (the default) or `--format json`, and the arguments without it; null on a wrong value. */
private class Format(
    val json: Boolean,
    val rest: List<String>,
) {
    companion object {
        fun of(args: List<String>): Format? {
            val index = args.indexOf("--format")
            if (index < 0) return Format(false, args)
            val value = args.getOrNull(index + 1) ?: return null
            if (value != "json" && value != "text") return null
            return Format(value == "json", args.take(index) + args.drop(index + 2))
        }
    }
}

/**
 * `rialto validate --format json`: one JSON document on stdout, for tools such as editors and CI annotations.
 * Its form is described in docs/diagnostics.md; the specification lists it as informative.
 */
private fun validateJson(
    files: List<String>,
    remote: RemoteMode,
    out: PrintStream,
    err: PrintStream,
): Int {
    if (files.isEmpty()) return usage(err)
    val root = ContractMapper.json.createObjectNode()
    val contracts = root.putArray("contracts")
    var failed = false
    files.map(Path::of).forEach { file ->
        val entry = contracts.addObject().put("file", file.toString())
        if (!file.exists()) {
            entry.put("valid", false)
            entry
                .putArray("diagnostics")
                .addObject()
                .put("severity", "error")
                .putNull("code")
                .put("message", "$file not found")
            failed = true
            return@forEach
        }

        val result = ContractValidator.validate(file, remote)
        entry.put("valid", result.valid)
        val diagnostics = entry.putArray("diagnostics")
        result.diagnostics.forEach { diagnostics.add(json(it)) }

        if (!result.valid) failed = true
    }

    root.put("valid", !failed)
    out.println(ContractMapper.json.writerWithDefaultPrettyPrinter().writeValueAsString(root))
    return if (failed) 1 else 0
}

private fun json(diagnostic: Diagnostic): ObjectNode {
    val node = ContractMapper.json.createObjectNode()
    node.put("severity", if (diagnostic.severity == Severity.ERROR) "error" else "warning")
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
