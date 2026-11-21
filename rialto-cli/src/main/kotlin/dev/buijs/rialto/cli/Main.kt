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

import kotlin.system.exitProcess

/**
 * The entry point of the `rialto` command: hands the exit code of [run] to the operating system. All behavior
 * lives in [run], which the tests call in process; this file is left out of the coverage, since exiting ends
 * the test JVM.
 */
fun main(args: Array<String>) {
    exitProcess(run(args.toList(), System.out, System.err))
}
