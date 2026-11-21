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

package dev.buijs.rialto.conformance

import dev.buijs.rialto.contract.relativePath
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.name
import kotlin.io.path.readLines
import kotlin.io.path.readText

/**
 * How one test contract fared.
 *
 * @property name the test contract, relative to `examples/contracts`, such as `invalid/unknown-type.yaml`
 * @property failure why it failed, or null when it passed
 * @property output what the command printed for it, stdout and stderr together
 */
data class ConformanceResult(
    val name: String,
    val failure: String?,
    val output: String,
) {
    val passed: Boolean get() = failure == null
}

/**
 * The conformance runner: runs a command once per test contract of the specification and judges what it does.
 *
 * The test contracts are the referee of the specification. Each is a small contract that is valid, or invalid
 * with the codes it must be rejected with in its leading `# expect: RIALTO-…` lines. A tool conforms when it
 * accepts every valid one and rejects every invalid one with its codes. The command can be any program in any
 * language: this tooling itself (`rialto validate`), or the tool of someone else.
 *
 * - A valid test contract passes when the command exits with 0.
 * - An invalid test contract passes when the command exits with another status and its output names every
 *   expected code.
 */
object Conformance {
    /** Where the test contracts are, in a checkout of rialto-spec or in its unpacked artifact. */
    const val CONTRACTS = "examples/contracts"

    /** The root of a test contract over several files, in a directory of its own. */
    private const val ROOT_FILE = "contract.rialto.yaml"

    /** How long the command gets for one test contract. */
    val TIMEOUT: Duration = Duration.ofSeconds(60)

    /** In a command: where the path of the test contract goes. */
    private const val PLACEHOLDER = "{contract}"

    /** A leading comment line of an invalid test contract that names a code it must be rejected with. */
    private val EXPECT = Regex("""^# expect: (RIALTO-[A-Z]\d{3})""")

    /**
     * Runs [command] for every test contract below [spec], and returns how each fared: the valid ones first,
     * then the invalid ones, each kind in the order of its paths.
     *
     * The command runs in the directory of the test contract. Each `{contract}` in it is replaced by the path
     * of the test contract; without a `{contract}` the path is added as the last argument.
     *
     * @param spec a checkout of rialto-spec, or its unpacked artifact: the directory that holds [CONTRACTS]
     * @param jobs how many test contracts run at a time; more than one only for a command whose runs share
     *   nothing, such as `validate`
     * @return one result per test contract; empty when [spec] has no test contracts
     */
    fun run(
        spec: Path,
        command: List<String>,
        jobs: Int = 1,
        timeout: Duration = TIMEOUT,
    ): List<ConformanceResult> {
        require(command.isNotEmpty()) { "The command is empty" }

        require(jobs > 0) { "jobs must be at least 1, but is $jobs" }

        val contracts = spec.resolve(CONTRACTS)
        val testContracts =
            testContracts(contracts, Kind.VALID) + testContracts(contracts, Kind.INVALID)
        val runnable = withAbsoluteProgram(command)

        val pool = Executors.newFixedThreadPool(jobs)
        try {
            val tasks = testContracts.map { Callable { result(it, runnable, timeout) } }

            return pool.invokeAll(tasks).map { it.get() }
        } finally {
            pool.shutdown()
        }
    }

    /** Whether a test contract must be accepted or rejected; [directory] is where its kind is kept. */
    private enum class Kind(
        val directory: String,
    ) {
        VALID("valid"),
        INVALID("invalid"),
    }

    private class TestContract(
        val kind: Kind,
        val name: String,
        val file: Path,
    )

    /** What running the command for one test contract gave. */
    private sealed class Execution {
        /** The command ran to its end. */
        data class Finished(
            val status: Int,
            val output: String,
        ) : Execution()

        /** The command did not start, or did not end in time; [reason] says which. */
        data class Unfinished(
            val reason: String,
        ) : Execution()
    }

    /**
     * The test contracts of [kind] below [contracts], in the order of their paths: each `.yaml` file, and for
     * a contract over several files the root in its own directory.
     */
    private fun testContracts(
        contracts: Path,
        kind: Kind,
    ): List<TestContract> {
        val directory = contracts.resolve(kind.directory)
        if (!directory.isDirectory()) {
            return emptyList()
        }

        val files =
            Files.list(directory).use { entries ->
                entries.toList().mapNotNull { entry ->
                    when {
                        entry.isRegularFile() && entry.name.endsWith(".yaml") -> entry
                        entry.resolve(ROOT_FILE).isRegularFile() -> entry.resolve(ROOT_FILE)
                        else -> null
                    }
                }
            }

        return files
            .map { TestContract(kind, relativePath(contracts, it), it) }
            .sortedBy { it.name }
    }

    /** [command] with its program as an absolute path when it is a relative path to a file: every run has its own directory. */
    private fun withAbsoluteProgram(command: List<String>): List<String> {
        val program = Path.of(command.first())
        if ("/" !in command.first() || !program.exists()) {
            return command
        }

        return listOf(program.toAbsolutePath().toString()) + command.drop(1)
    }

    private fun result(
        contract: TestContract,
        command: List<String>,
        timeout: Duration,
    ): ConformanceResult =
        when (val execution = execute(command, contract.file, timeout)) {
            is Execution.Unfinished -> {
                ConformanceResult(contract.name, execution.reason, "")
            }
            is Execution.Finished -> {
                ConformanceResult(contract.name, judge(contract, execution), execution.output)
            }
        }

    /** Runs [command] for the test contract [file], in its directory, with its output in a temporary file. */
    private fun execute(
        command: List<String>,
        file: Path,
        timeout: Duration,
    ): Execution {
        val output = Files.createTempFile("rialto-conformance", ".txt")
        try {
            val process =
                try {
                    ProcessBuilder(arguments(command, file))
                        .directory(file.parent.toFile())
                        .redirectErrorStream(true)
                        .redirectOutput(output.toFile())
                        .start()
                } catch (e: IOException) {
                    return Execution.Unfinished("cannot start the command: ${e.message}")
                }

            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                // A command that starts another program, such as a start script, must not leave that running.
                process.descendants().forEach { it.destroyForcibly() }

                process.destroyForcibly()
                return Execution.Unfinished("timed out after ${timeout.toSeconds()} s")
            }

            return Execution.Finished(process.exitValue(), output.readText())
        } finally {
            Files.deleteIfExists(output)
        }
    }

    /** [command] for the test contract [file]: each `{contract}` replaced by its path, or the path as last argument. */
    private fun arguments(
        command: List<String>,
        file: Path,
    ): List<String> {
        val path = file.toAbsolutePath().toString()
        if (command.none { PLACEHOLDER in it }) {
            return command + path
        }

        return command.map { it.replace(PLACEHOLDER, path) }
    }

    /** Why [contract] failed, or null when it passed. */
    private fun judge(
        contract: TestContract,
        execution: Execution.Finished,
    ): String? =
        when (contract.kind) {
            Kind.VALID -> judgeValid(execution)
            Kind.INVALID -> judgeInvalid(expectedCodes(contract.file), execution)
        }

    private fun judgeValid(execution: Execution.Finished): String? {
        if (execution.status == 0) {
            return null
        }

        return "exit ${execution.status}, expected 0"
    }

    private fun judgeInvalid(
        codes: List<String>,
        execution: Execution.Finished,
    ): String? {
        if (codes.isEmpty()) {
            return "names no expected code (# expect: RIALTO-…)"
        }

        if (execution.status == 0) {
            return "exit 0, expected a failure with ${codes.joinToString(", ")}"
        }

        val missing = codes.filterNot { Regex("""\b$it\b""").containsMatchIn(execution.output) }

        if (missing.isNotEmpty()) {
            return "missing ${missing.joinToString(", ")}"
        }

        return null
    }

    /** The codes the leading comment lines of an invalid test contract name. */
    private fun expectedCodes(file: Path): List<String> =
        file
            .readLines()
            .takeWhile { it.startsWith("#") }
            .mapNotNull { EXPECT.find(it)?.groupValues?.get(1) }
}
