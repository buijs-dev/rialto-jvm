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

package dev.buijs.rialto.gradle

import java.io.File

/**
 * The test contracts of the plugin tests, in `src/test/resources/contracts`: copies of `valid/minimal.yaml`,
 * `invalid/unknown-type.yaml` and `valid/fragments` of the specification's test contracts. The plugin tests check
 * the wiring of the tasks, not the rules; rialto-core and rialto-cli test the rules against all test contracts.
 */
internal fun testContracts(): File = File(object {}.javaClass.getResource("/contracts")!!.toURI())
