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
import dev.buijs.rialto.core.document.ContractMapper
import dev.buijs.rialto.core.model.Contract
import dev.buijs.rialto.core.validation.ContractValidator
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DynamicTest
import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.ArrayNode
import tools.jackson.databind.node.ObjectNode
import tools.jackson.module.kotlin.treeToValue
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeText

/** One change to the Shipment example: [value] at [pointer], and the one finding it must give. */
internal data class Case(
    val pointer: String,
    val value: Any,
    val code: Code,
    val at: String = pointer,
    /** A table that does not exist also leaves its columns unresolved: then the first finding is this one. */
    val first: Boolean = false,
)

/** A change that leaves a name unresolved (`RIALTO-K004`). */
internal fun ref(
    pointer: String,
    value: Any = "Nope",
    at: String = pointer,
) = Case(pointer = pointer, value = value, code = Code.UNRESOLVED_REFERENCE, at = at)

/** A change that points into a source at a location it lacks (`RIALTO-S002`). */
internal fun external(
    pointer: String,
    value: Any,
    at: String = pointer,
) = Case(pointer = pointer, value = value, code = Code.SOURCE_REFERENCE, at = at)

/**
 * The Shipment example of the test resources, which uses the whole format, copied with its sources into [work]:
 * each test changes it in a few places and validates the result.
 */
internal class ShipmentExample(
    private val work: Path,
) {
    private val shipment = Path.of(javaClass.getResource("/shipment")!!.toURI())

    private val original: ObjectNode by lazy {
        listOf("openapi.json", "openapi-v1.json", "asyncapi.json", "schema.sql").forEach {
            Files.copy(shipment.resolve(it), work.resolve(it))
        }

        ContractMapper.yaml.readTree(shipment.resolve("shipment.rialto.yaml").toFile()).asObject()
    }

    private var written = 0

    /** The code and pointer of each finding of the example with [changes], a value by pointer. */
    fun findings(changes: Map<String, Any>): List<Pair<Code, String>> {
        val document = original.deepCopy()
        changes.forEach { (pointer, value) ->
            set(root = document, pointer = pointer, value = ContractMapper.json.valueToTree(value))
        }

        val file = work.resolve("case-${written++}.rialto.yaml")
        file.writeText(ContractMapper.yaml.writeValueAsString(document))
        return ContractValidator.validate(file).diagnostics.map { it.code to it.location.pointer }
    }

    /** One test per case: the change gives the finding of the case, and nothing else. */
    fun invalid(cases: List<Case>): List<DynamicTest> =
        cases.map { case ->
            DynamicTest.dynamicTest("${case.pointer} = ${case.value}") {
                // when
                val found = findings(mapOf(case.pointer to case.value))

                // then
                if (case.first) {
                    val parent = case.at.substringBeforeLast('/')
                    assertThat(found.first()).isEqualTo(case.code to case.at)
                    assertThat(found).allMatch { it.first == case.code && it.second.startsWith(parent) }
                } else {
                    assertThat(found).containsExactly(case.code to case.at)
                }
            }
        }

    /** One test per set of changes: the example stays valid. */
    fun valid(changes: List<Map<String, Any>>): List<DynamicTest> =
        changes.map { change ->
            DynamicTest.dynamicTest(change.keys.joinToString()) {
                assertThat(findings(change)).isEmpty()
            }
        }

    /** Sets [value] at [pointer], adding the last segment when it is a new key. */
    private fun set(
        root: JsonNode,
        pointer: String,
        value: JsonNode,
    ) {
        val parent = root.at(pointer.substringBeforeLast('/'))
        val key = pointer.substringAfterLast('/').replace("~1", "/").replace("~0", "~")
        when (parent) {
            is ObjectNode -> parent.set(key, value)
            is ArrayNode -> parent.set(key.toInt(), value)
            else -> error("No parent for $pointer")
        }
    }
}

/** The contract model of [sections], YAML below the `rialto` and `info` every contract has; nothing is validated. */
internal fun contractOf(sections: String): Contract =
    ContractMapper.json.treeToValue<Contract>(
        ContractMapper.yaml.readTree("rialto: '1.0.0'\ninfo: {name: test, version: 1.0.0}\n" + sections.trimIndent()),
    )

/** A name reference at [pointer], as a section lists it. */
internal fun nameAt(
    kind: Kind,
    name: String,
    pointer: String,
) = Reference.Name(kind = kind, name = name, pointer = Pointer(pointer))

/** An external reference at [pointer], as a section lists it. */
internal fun externalAt(
    reference: String,
    pointer: String,
) = Reference.External(reference = reference, pointer = Pointer(pointer))
