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
import dev.buijs.rialto.core.sources.ExternalRef
import dev.buijs.rialto.core.sources.RestOperation
import dev.buijs.rialto.core.sources.Sources
import dev.buijs.rialto.core.validation.CheckContext

/**
 * Checks that each [Reference] resolves (K1.2): a name must be declared (`RIALTO-K004`), and a
 * `<source>#<location>` must land in a declared source (`RIALTO-K004`) at a location it has (`RIALTO-S002`).
 */
internal class ReferenceChecks(
    private val context: CheckContext,
) {
    private val contract = context.contract

    private val declared: Map<Kind, Set<String>> =
        Kind.entries.associateWith { kind ->
            when (kind) {
                Kind.TYPE -> {
                    contract.types.keys
                }
                Kind.DATA -> {
                    contract.types.keys + contract.shapes.keys
                }
                Kind.SHAPE -> {
                    contract.shapes.keys
                }
                Kind.OPERATION -> {
                    contract.operations.keys
                }
                Kind.EVENT -> {
                    contract.events.keys
                }
                Kind.VIEW -> {
                    contract.views.keys
                }
                Kind.PROVIDER -> {
                    contract.auth
                        ?.providers
                        ?.keys
                        .orEmpty()
                }
                Kind.SOURCE -> {
                    contract.sources.keys
                }
            }
        }

    fun check(reference: Reference) =
        when (reference) {
            is Reference.Name -> name(reference)
            is Reference.External -> external(reference)
        }

    private fun name(reference: Reference.Name) {
        if (reference.name !in declared.getValue(reference.kind)) {
            context.error(
                code = Code.UNRESOLVED_REFERENCE,
                message = "Unknown ${reference.kind.label} '${reference.name}'",
                pointer = reference.pointer.text,
            )
        }
    }

    /** A declared source that could not be loaded was reported when loading, so it is not reported again here. */
    private fun external(reference: Reference.External) {
        val ref = ExternalRef.of(reference.reference)
        val resolution = context.sources.resolve(reference.reference, contract.sources.keys)
        when (resolution) {
            Sources.Resolution.UnknownSource -> {
                context.error(
                    code = Code.UNRESOLVED_REFERENCE,
                    message = "Unknown source '${ref.source}' in '${reference.reference}'",
                    pointer = reference.pointer.text,
                )
            }
            is Sources.Resolution.Missing -> {
                context.error(
                    code = Code.SOURCE_REFERENCE,
                    message = "'${ref.location}' does not exist in ${resolution.source.file.fileName}",
                    pointer = reference.pointer.text,
                )
            }
            Sources.Resolution.Resolved, Sources.Resolution.Unloaded -> {
            }
        }

        if (resolution == Sources.Resolution.Resolved) {
            operation(reference, ref)
        }
    }

    /** A REST binding must land on an operation, not on the path item or an element inside the operation. */
    private fun operation(
        reference: Reference.External,
        ref: ExternalRef,
    ) {
        if (reference.operation && RestOperation.of(reference.reference) == null) {
            context.error(
                code = Code.SOURCE_REFERENCE,
                message = "'${ref.location}' is not an OpenAPI operation /paths/<path>/<method>",
                pointer = reference.pointer.text,
            )
        }
    }
}
