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

import com.networknt.schema.Schema
import tools.jackson.core.JacksonException
import tools.jackson.databind.DatabindException
import tools.jackson.module.kotlin.treeToValue
import java.nio.file.Path

/** The outcome of validating one contract: the model when it is usable, and every finding. */
data class ValidationResult(
    val contract: Contract?,
    val diagnostics: List<Diagnostic>,
) {
    val valid: Boolean get() =
        contract != null &&
            diagnostics.none { it.severity == Severity.ERROR }
}

/**
 * Loads and validates a contract: format version (K1.1), JSON Schema (K1.7), its sources (K1.9, K4), and then
 * each set of [checks] over the model: the references and names of the format (K1.2, K1.3, K2.1), and the rules
 * of each profile, which its own set checks.
 */
object ContractValidator {
    const val SUPPORTED_VERSION = "1.0.0"

    /** The checks over the contract model, in order; each profile adds its own set. */
    internal val checks: List<ContractChecks> =
        listOf(
            FormatChecks,
        )

    private val schema: Schema by lazy {
        val stream =
            requireNotNull(javaClass.getResourceAsStream("contract-1.0.0.schema.json")) {
                "Schema resource missing"
            }

        stream.use { ContractFiles.schemaRegistry.getSchema(it) }
    }

    fun validate(
        file: Path,
        remote: RemoteMode = RemoteMode.LOCKED,
    ): ValidationResult {
        val diagnostics = Diagnostics()
        // The bundled form of a contract over several files (K5); a single file is read as it is.
        val document =
            ContractFiles.read(file, diagnostics) ?: return ValidationResult(null, diagnostics.all)
        if (diagnostics.hasErrors) return ValidationResult(null, diagnostics.all)

        val version = document.root.path("rialto").asString("")
        if (version != SUPPORTED_VERSION) {
            diagnostics.error(
                Code.UNSUPPORTED_VERSION,
                "Contract format '$version' is not supported",
                document.locate("/rialto"),
                "This tool reads format $SUPPORTED_VERSION",
            )
            return ValidationResult(null, diagnostics.all)
        }

        schema
            .validate(document.root)
            .sortedBy { it.instanceLocation.toString() }
            .forEach { message ->
                val pointer =
                    message.instanceLocation.toString().let {
                        if (it == "/" || it == "$") {
                            ""
                        } else {
                            it
                        }
                    }

                diagnostics.error(
                    Code.SCHEMA,
                    message.message,
                    document.locate(pointer),
                )
            }

        if (diagnostics.hasErrors) return ValidationResult(null, diagnostics.all)

        val contract =
            model(document, diagnostics) ?: return ValidationResult(null, diagnostics.all)
        val sources = Sources.load(contract, document, diagnostics, remote)
        val context = CheckContext(contract, document, sources, diagnostics)
        checks.forEach { it.run(context) }

        return ValidationResult(contract, diagnostics.all)
    }

    /**
     * The model of a contract that is valid against the JSON Schema, which it should always fit. When it does not,
     * that is a gap between the schema and the model: reported as K002 at the root, so the caller gets a finding, not
     * a crash.
     */
    internal fun model(
        document: LocatedDocument,
        diagnostics: Diagnostics,
    ): Contract? =
        try {
            ContractMapper.json.treeToValue<Contract>(document.root)
        } catch (e: Exception) {
            diagnostics.error(
                Code.SCHEMA,
                "the contract passes the JSON Schema but does not fit the contract model${misfit(
                    e,
                )}",
                document.locate(""),
            )
            null
        }

    /**
     * Where and why the model rejects a document: the path of [e], then the deepest Jackson cause, which a type
     * that reads its own node (a delegating creator) throws with the rest of the path.
     */
    private fun misfit(e: Exception): String {
        val at =
            (e as? DatabindException)?.path.orEmpty().joinToString("") {
                "/" +
                    (it.propertyName ?: it.index)
            }

        val cause =
            generateSequence<Throwable>(
                e,
            ) { it.cause }.filterIsInstance<JacksonException>().lastOrNull() ?: e
        return (if (at.isEmpty()) "" else " at $at") + ": " + cause.message
    }
}
