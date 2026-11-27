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

package dev.buijs.rialto.core.conformance

import java.nio.file.Path
import kotlin.io.path.readLines
import kotlin.system.exitProcess

/**
 * A stand-in for the tool under test, run as a program of its own. It reads the test contract, its last argument,
 * and does what each `# tool: <action> <value>` line of that contract says, in order:
 *
 * - `print <text>` and `error <text>` write to stdout and stderr;
 * - `arguments` prints the arguments it was started with, and `directory` the directory it runs in;
 * - `sleep` waits longer than any test allows;
 * - `exit <status>` ends with that status.
 */
object FakeTool {
    private const val PREFIX = "# tool: "

    @JvmStatic
    fun main(args: Array<String>) {
        val actions =
            Path
                .of(args.last())
                .readLines()
                .filter { it.startsWith(PREFIX) }
                .map { it.removePrefix(PREFIX) }

        actions.forEach { action ->
            val value = action.substringAfter(' ', "")
            when (action.substringBefore(' ')) {
                "print" -> println(value)
                "error" -> System.err.println(value)
                "arguments" -> println(args.joinToString(" | "))
                "directory" -> println(Path.of("").toAbsolutePath())
                "sleep" -> Thread.sleep(SLEEP_MILLIS)
                "exit" -> exitProcess(value.toInt())
            }
        }
    }

    private const val SLEEP_MILLIS = 60_000L
}
