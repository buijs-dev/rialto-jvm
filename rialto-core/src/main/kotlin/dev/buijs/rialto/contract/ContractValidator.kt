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

import tools.jackson.core.JacksonException
import tools.jackson.module.kotlin.treeToValue
import java.nio.file.Path

/**
 * A contract that passed validation: the model, with the document it was read from and its loaded sources.
 *
 * This is what later slices (generators, `diff`) take as their input. It only exists inside an [Outcome.Ok] of
 * [ContractValidator.validate], so they never read a contract that has not passed validation, and they do not
 * read the document or load the sources a second time.
 *
 * @property contract the model
 * @property document the bundled form the model was mapped from, to point at a file and line
 * @property sources the OpenAPI, AsyncAPI and SQL sources of the contract, every one of them loaded
 */
class ValidContract(
    val contract: Contract,
    val document: LocatedDocument,
    val sources: Sources,
)

/**
 * The entry point of the library: loads and validates a contract. `rialto validate` and the Gradle task
 * `rialtoValidate` are thin wrappers around [validate]; everything else in this package serves one of its steps.
 *
 * The steps run in order, and each one only starts when the steps before it found no error, because each
 * relies on what the earlier ones guarantee:
 * 1. read the file, or the bundled form of a contract over several files ([ContractFiles], K5);
 * 2. check the format version (K1.1), so a contract of another version is not judged by this schema;
 * 3. check the JSON Schema (K1.7), so the document has the structure the model expects;
 * 4. map the tree onto the [Contract] model ([model]);
 * 5. load the OpenAPI, AsyncAPI and SQL sources ([Sources], K1.9, K4), local or remote;
 * 6. run each set of [checks] over the model: the references and names of the format (K1.2, K1.3, K2.1), and,
 *    from slice 1 on, the rules of each profile.
 *
 * Every finding is a [Diagnostic] with the file and line it is about, returned in the [Outcome]; [validate] does
 * not throw on an invalid contract.
 */
object ContractValidator {
    /** The one version of the contract format (the `rialto` key) this tooling reads. */
    const val SUPPORTED_VERSION = "1.0.0"

    /**
     * The checks over the contract model, in order; each profile adds its own set. This list is the extension
     * point of the validator: a new rule is a new [ContractChecks], not a change to [validate].
     */
    internal val checks: List<ContractChecks> =
        listOf(
            FormatChecks,
        )

    /**
     * Validates the contract at [file]: a single file, or the root of a contract over several files.
     *
     * @param remote how remote sources are read: [RemoteMode.LOCKED] for a normal run, [RemoteMode.OFFLINE] for
     *   `--offline`; a run may write `rialto.lock` when it pins a new remote source
     * @return [Outcome.Ok] with the valid contract and any warnings, or [Outcome.Nok] with every finding, in the
     *   order the steps made them
     */
    fun validate(
        file: Path,
        remote: RemoteMode = RemoteMode.LOCKED,
    ): Outcome<ValidContract> {
        val findings = mutableListOf<Diagnostic>()

        // 1. The bundled form of a contract over several files (K5); a single file is read as it is.
        val document =
            when (val read = ContractFiles.read(file)) {
                is Outcome.Nok -> {
                    return read
                }
                is Outcome.Ok -> {
                    findings += read.diagnostics
                    read.value
                }
            }

        // 2. The format version.
        val version = document.root.path(Keys.RIALTO).asString("")
        if (version != SUPPORTED_VERSION) {
            findings +=
                Diagnostic.error(
                    Code.UNSUPPORTED_VERSION,
                    "Contract format '$version' is not supported",
                    document.locate("/${Keys.RIALTO}"),
                    "This tool reads format $SUPPORTED_VERSION",
                )
            return Outcome.Nok(findings)
        }

        // 3. The JSON Schema.
        val invalid = ContractSchema.check(ContractSchema.contract, document.root, document)
        if (invalid.isNotEmpty()) {
            return Outcome.Nok(findings + invalid)
        }

        // 4. The model.
        val contract =
            when (val modelled = model(document)) {
                is Outcome.Nok -> {
                    return Outcome.Nok(findings + modelled.diagnostics)
                }
                is Outcome.Ok -> {
                    findings += modelled.diagnostics
                    modelled.value
                }
            }

        // 5. The sources. One that cannot be loaded is a finding, but the checks still run, so the author sees
        // every mistake in one run.
        val sources = Sources.load(contract, document, remote)
        findings += sources.diagnostics

        // 6. The checks over the model.
        val context = CheckContext(contract, document, sources)
        checks.forEach { it.run(context) }

        findings += context.diagnostics

        return Outcome.of(ValidContract(contract, document, sources), findings)
    }

    /**
     * The model of a contract that is valid against the JSON Schema, which it should always fit. When it does not,
     * that is a gap between the schema and the model: [Outcome.Nok] with a K002 at the element the model rejects,
     * so the caller gets a finding, not a crash.
     */
    internal fun model(document: LocatedDocument): Outcome<Contract> =
        try {
            Outcome.Ok(ContractMapper.json.treeToValue<Contract>(document.root))
        } catch (e: JacksonException) {
            // Jackson wraps every failure of a creator too, so this is the only exception mapping throws.
            Outcome.Nok(listOf(misfit(e, document)))
        }
}

/**
 * The finding for a document the model rejects: where, from the path of [e], and why, from the deepest Jackson
 * cause, which a type that reads its own node (a delegating creator) throws with the rest of the path.
 */
private fun misfit(
    e: JacksonException,
    document: LocatedDocument,
): Diagnostic {
    val path = e.path.map { (it.propertyName ?: it.index).toString() }

    val place = if (path.isEmpty()) "" else " at /" + path.joinToString("/")

    val cause =
        generateSequence<Throwable>(e) { it.cause }
            .filterIsInstance<JacksonException>()
            .last()

    val message =
        "the contract passes the JSON Schema but does not fit the contract model$place: ${cause.message}"
    val pointer = path.joinToString("") { "/" + LocatedDocument.segment(it) }

    return Diagnostic.error(Code.SCHEMA, message, document.locate(pointer))
}
