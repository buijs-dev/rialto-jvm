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

package dev.buijs.rialto.core.schema

import com.networknt.schema.Schema
import com.networknt.schema.SchemaRegistry
import com.networknt.schema.SchemaRegistryConfig
import com.networknt.schema.SpecificationVersion
import com.networknt.schema.path.PathType
import dev.buijs.rialto.core.diagnostics.Code
import dev.buijs.rialto.core.diagnostics.Diagnostic
import dev.buijs.rialto.core.diagnostics.Severity
import dev.buijs.rialto.core.document.ContractMapper
import dev.buijs.rialto.core.document.LocatedDocument
import dev.buijs.rialto.core.model.Keys
import tools.jackson.databind.JsonNode

/**
 * The JSON Schema of the contract format (K1.7), read once from the resource that must equal the
 * specification's byte for byte, and the three schemas cut from it: the whole contract, the root's `include`,
 * and a fragment of a contract over several files (K5).
 *
 * Every schema check of this package goes through [check], so each returns its findings the same way: as
 * `RIALTO-K002`, sorted by place, at the file and line the element was read from.
 */
internal object ContractSchema {
    private val tree: JsonNode by lazy {
        // Part of this library's jar; the tests check it equals the specification's.
        ContractSchema::class.java.getResourceAsStream("contract-1.0.0.schema.json")!!.use {
            ContractMapper.json.readTree(it)
        }
    }

    /** The JSON Schema dialect of the contract schema (2020-12), with format assertions and JSON Pointer locations. */
    private val registry: SchemaRegistry =
        SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12) {
            it.schemaRegistryConfig(
                SchemaRegistryConfig
                    .builder()
                    .formatAssertionsEnabled(true)
                    .pathType(PathType.JSON_POINTER)
                    .build(),
            )
        }

    /** The schema of a whole contract, in its bundled form. */
    val contract: Schema by lazy { registry.getSchema(tree) }

    /** The schema of the root's `include` (K5.2), which the bundled form drops. */
    val include: Schema by lazy {
        val include =
            tree.path("properties").path(Keys.INCLUDE).asObject().deepCopy().apply {
                put($$"$schema", tree.path($$"$schema").asString())
            }

        registry.getSchema(include)
    }

    /** The schema of a fragment (K5.2): the definitions of the contract with `fragment` at the top. */
    val fragment: Schema by lazy {
        val fragment =
            ContractMapper.json.createObjectNode().apply {
                set($$"$defs", tree.get($$"$defs"))
                set("properties", tree.get("properties"))
                put($$"$ref", $$"#/$defs/fragment")
            }

        registry.getSchema(fragment)
    }

    /**
     * Validates [node] against [schema] and returns each finding as a `RIALTO-K002` error at its place in
     * [document]; [at] is the pointer of [node] in [document]. An empty list means [node] is valid.
     */
    fun check(
        schema: Schema,
        node: JsonNode,
        document: LocatedDocument,
        at: String = "",
    ): List<Diagnostic> {
        val findings = schema.validate(node).sortedBy { it.instanceLocation.toString() }

        // With JSON Pointer locations the root is the empty pointer, so a location appends to [at] as it is.
        return findings.map { message ->
            Diagnostic(
                severity = Severity.ERROR,
                code = Code.SCHEMA,
                message = message.message,
                location = document.locate(at + message.instanceLocation),
            )
        }
    }
}
