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

package dev.buijs.rialto.core.validation.format

import dev.buijs.rialto.core.diagnostics.Code
import dev.buijs.rialto.core.model.Keys
import dev.buijs.rialto.core.validation.CheckContext
import dev.buijs.rialto.core.validation.ContractChecks

/** No name is both a type and a shape (K1.3, `RIALTO-K005`): a reference to it would be ambiguous. */
internal object NameChecks : ContractChecks {
    override fun run(context: CheckContext) {
        val contract = context.contract
        contract.shapes.keys.filter { it in contract.types }.forEach {
            context.error(
                code = Code.DUPLICATE_NAME,
                message = "'$it' is both a type and a shape",
                pointer = Pointer.of(Keys.SHAPES, it).text,
            )
        }
    }
}
