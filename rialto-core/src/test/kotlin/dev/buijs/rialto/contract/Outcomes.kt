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

/** The value of an outcome a test expects to be [Outcome.Ok]; the test fails with the findings when it is not. */
internal fun <T> Outcome<T>.valueOrFail(): T =
    when (this) {
        is Outcome.Ok -> value
        is Outcome.Nok -> throw AssertionError("Expected Ok, but found: $diagnostics")
    }
