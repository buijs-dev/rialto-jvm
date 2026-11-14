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

import com.fasterxml.jackson.module.kotlin.treeToValue
import com.networknt.schema.JsonSchema
import com.networknt.schema.JsonSchemaFactory
import com.networknt.schema.PathType
import com.networknt.schema.SchemaValidatorsConfig
import com.networknt.schema.SpecVersion
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
            ContractChecks { ReferenceChecks(it).run() },
        )

    private val schema: JsonSchema by lazy {
        val config =
            SchemaValidatorsConfig
                .builder()
                .formatAssertionsEnabled(true)
                .pathType(PathType.JSON_POINTER)
                .build()
        val stream =
            requireNotNull(javaClass.getResourceAsStream("contract-1.0.0.schema.json")) {
                "Schema resource missing"
            }

        stream.use {
            JsonSchemaFactory
                .getInstance(
                    SpecVersion.VersionFlag.V202012,
                ).getSchema(it, config)
        }
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

        val version = document.root.path("rialto").asText("")
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
                    message.message.substringAfter(": ", message.message),
                    document.locate(pointer),
                )
            }

        if (diagnostics.hasErrors) return ValidationResult(null, diagnostics.all)

        val contract =
            try {
                ContractMapper.json.treeToValue<Contract>(document.root)
            } catch (e: Exception) {
                diagnostics.error(
                    Code.INTERNAL,
                    "Cannot build the contract model: ${e.message}",
                    document.locate(""),
                )
                return ValidationResult(null, diagnostics.all)
            }

        val sources = Sources.load(contract, document, diagnostics, remote)
        val context = CheckContext(contract, document, sources, diagnostics)
        checks.forEach { it.run(context) }

        return ValidationResult(contract, diagnostics.all)
    }
}
