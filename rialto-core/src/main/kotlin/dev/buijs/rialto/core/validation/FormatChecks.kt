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

package dev.buijs.rialto.core.validation

import dev.buijs.rialto.core.validation.format.NameChecks
import dev.buijs.rialto.core.validation.format.PropertyPathChecks
import dev.buijs.rialto.core.validation.format.ReferenceChecks
import dev.buijs.rialto.core.validation.format.TypeExpressionChecks
import dev.buijs.rialto.core.validation.format.authReferences
import dev.buijs.rialto.core.validation.format.eventReferences
import dev.buijs.rialto.core.validation.format.mappingReferences
import dev.buijs.rialto.core.validation.format.navigationReferences
import dev.buijs.rialto.core.validation.format.operationReferences
import dev.buijs.rialto.core.validation.format.shapeReferences
import dev.buijs.rialto.core.validation.format.slotExpressions
import dev.buijs.rialto.core.validation.format.sourceReferences
import dev.buijs.rialto.core.validation.format.storageReferences
import dev.buijs.rialto.core.validation.format.typeExpressions
import dev.buijs.rialto.core.validation.format.typeReferences
import dev.buijs.rialto.core.validation.format.viewReferences

/**
 * The checks of the contract format itself, over every section: each reference to a contract element resolves
 * (K1.2, `RIALTO-K004`), and so does each external reference into a source (`RIALTO-S002`); no name is both a type
 * and a shape (K1.3, `RIALTO-K005`); and each type expression is valid (K2.1, `RIALTO-K006`).
 *
 * The JSON Schema only checks the structure of each element on its own; it cannot see that a name used in one
 * place is declared in another. These checks close that gap for slice 0, so a valid contract is a connected
 * whole: every name it uses exists, and every `<source>#<location>` lands in a loaded source. The profile checks
 * of later slices build on that and can look names up without checking them again.
 *
 * Each section lists what it refers to and which type expressions it holds (the `format` package); the rules
 * ([ReferenceChecks], [TypeExpressionChecks], [PropertyPathChecks], [NameChecks]) judge those. The sections are
 * checked in the order of the document, so the findings of one section stay together.
 */
internal object FormatChecks : ContractChecks {
    override fun run(context: CheckContext) {
        val contract = context.contract
        val references = ReferenceChecks(context)
        val expressions = TypeExpressionChecks(context, references)
        val paths = PropertyPathChecks(context)

        NameChecks.run(context)

        contract.typeExpressions().forEach(expressions::check)
        contract.typeReferences().forEach(references::check)
        paths.projections()

        contract.shapeReferences().forEach(references::check)
        paths.shapes()

        contract.operationReferences().forEach(references::check)
        contract.eventReferences().forEach(references::check)
        contract.mappingReferences().forEach(references::check)
        contract.storageReferences().forEach(references::check)

        contract.viewReferences().forEach(references::check)
        contract.slotExpressions().forEach(expressions::check)
        contract.navigationReferences().forEach(references::check)

        contract.authReferences().forEach(references::check)
        contract.sourceReferences().forEach(references::check)
    }
}
