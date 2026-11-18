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

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

/** Local sources (K1.9): OpenAPI and AsyncAPI documents, SQL files and migrations, and references into them. */
class SourcesTest {
    @TempDir
    lateinit var work: Path

    private val contract: Path get() = work.resolve("contract.rialto.yaml")

    private fun file(
        name: String,
        text: String,
    ) = work.resolve(name).also { it.parent.createDirectories() }.writeText(text)

    /** Validates a contract with [sources] and the [rest] of its sections. */
    private fun validate(
        sources: String,
        rest: String = "",
    ): List<Diagnostic> {
        contract.writeText(
            "rialto: '1.0.0'\ninfo: {name: x, version: 1.0.0}\nsources:\n$sources$rest",
        )
        return ContractValidator.validate(contract).diagnostics
    }

    private val api = "  api: {kind: openapi, path: api.json, version: 1.0.0, status: current}\n"
    private val db = "  db: {kind: sql, path: schema.sql, dialect: postgresql}\n"
    private val thing =
        "types:\n  Thing: {kind: entity, identity: [id], properties: {id: {type: uuid}}}\n"

    private fun mapping(source: String) =
        "mappings:\n  - {source: '$source', target: Thing, direction: both}\n"

    private fun storage(table: String) =
        "storage:\n  Thing: {table: '$table', columns: {id: public_id}}\n"

    @Test
    fun `a source that does not exist or cannot be parsed is RIALTO-S001 at its path`() {
        val missing = validate(api).single()
        assertThat(missing.code).isEqualTo(Code.SOURCE_UNREADABLE)
        assertThat(missing.message).isEqualTo("Source 'api' not found: api.json")
        assertThat(missing.location.pointer).isEqualTo("/sources/api/path")
        assertThat(missing.location.line).isEqualTo(4)

        file("api.json", "{ not json")
        val broken = validate(api).single()
        assertThat(broken.code).isEqualTo(Code.SOURCE_UNREADABLE)
        assertThat(broken.message).startsWith("Source 'api' cannot be read: ")
    }

    @Test
    fun `a reference into a document resolves by JSON Pointer, else RIALTO-S002`() {
        file(
            "api.json",
            """{"openapi": "3.1.0", "components": {"schemas": {"Thing": {"type": "object"}}}}""",
        )
        assertThat(validate(api, thing + mapping("api#/components/schemas/Thing"))).isEmpty()

        val missing = validate(api, thing + mapping("api#/components/schemas/Other")).single()
        assertThat(missing.code).isEqualTo(Code.SOURCE_REFERENCE)
        assertThat(
            missing.message,
        ).isEqualTo("'/components/schemas/Other' does not exist in api.json")
        assertThat(missing.location.pointer).isEqualTo("/mappings/0/source")

        val document =
            Sources.load(
                ContractValidator.validate(contract).contract!!,
                LocatedDocument.read(contract),
                Diagnostics(),
            )
        assertThat(
            document.element("api#/components/schemas/Thing")!!.path("type").asString(),
        ).isEqualTo("object")
        assertThat(document.element("db#thing")).isNull()
        assertThat(document.table("api#thing")).isNull()
    }

    @Test
    fun `a reference to an undeclared source is RIALTO-K004, and one to a source that did not load is not reported again`() {
        file("api.json", """{"openapi": "3.1.0"}""")
        val unknown = validate(api, thing + mapping("other#/x")).single()
        assertThat(unknown.code).isEqualTo(Code.UNRESOLVED_REFERENCE)
        assertThat(unknown.message).isEqualTo("Unknown source 'other' in 'other#/x'")

        assertThat(validate(db, thing + storage("db#thing")).map { it.message })
            .containsExactly("Source 'db' not found: schema.sql")
    }

    @Test
    fun `a reference into SQL names a table, or a table and a column, in any case`() {
        file("schema.sql", "CREATE TABLE Thing (Public_Id UUID PRIMARY KEY, name TEXT);")
        assertThat(validate(db, thing + storage("db#THING"))).isEmpty()

        val table = validate(db, thing + storage("db#other")).first()
        assertThat(table.code).isEqualTo(Code.SOURCE_REFERENCE)
        assertThat(table.message).isEqualTo("'other' does not exist in schema.sql")
        assertThat(table.location.pointer).isEqualTo("/storage/Thing/table")

        val column =
            validate(
                db,
                thing + "storage:\n  Thing: {table: db#thing, columns: {id: missing}}\n",
            ).single()
        assertThat(column.code).isEqualTo(Code.SOURCE_REFERENCE)
        assertThat(column.message).isEqualTo("'thing.missing' does not exist in schema.sql")
        assertThat(column.location.pointer).isEqualTo("/storage/Thing/columns/id")

        val sources =
            Sources.load(
                ContractValidator.validate(contract).contract!!,
                LocatedDocument.read(contract),
                Diagnostics(),
            )
        assertThat(sources.table("db#Thing")!!.columns.keys).containsExactly("public_id", "name")
        assertThat((sources.loaded["db"] as LoadedSource.Sql).tables).isEqualTo(
            mapOf("thing" to setOf("public_id", "name")),
        )
    }

    @Test
    fun `migrations are read in version order, the files without a version after them`() {
        file("db/V2__add.sql", "ALTER TABLE thing ADD COLUMN second TEXT;")
        file("db/V10__rename.sql", "ALTER TABLE thing RENAME COLUMN second TO tenth;")
        file("db/V1__create.sql", "CREATE TABLE thing (public_id UUID PRIMARY KEY)")
        file("db/V1_1__more.sql", "ALTER TABLE thing ADD COLUMN first TEXT;")
        file("db/nested/V3__nested.sql", "ALTER TABLE thing ADD COLUMN third TEXT;")
        file("db/b_repeatable.sql", "ALTER TABLE thing ADD COLUMN later TEXT;")
        file("db/a_repeatable.sql", "ALTER TABLE thing ADD COLUMN earlier TEXT;")
        file("db/notes.txt", "not sql")

        assertThat(
            migrations(work, "db/**/*.sql").map { work.relativize(it).joinToString("/") },
        ).containsExactly(
            "db/V1__create.sql",
            "db/V1_1__more.sql",
            "db/V2__add.sql",
            "db/nested/V3__nested.sql",
            "db/V10__rename.sql",
            "db/a_repeatable.sql",
            "db/b_repeatable.sql",
        )
        assertThat(migrations(work, "db/*.sql")).hasSize(6)
        assertThat(migrations(work, "db/**")).hasSize(8)
        assertThat(migrations(work, "missing/*.sql")).isEmpty()

        val source = "  db: {kind: sql, path: 'db/**/*.sql', dialect: postgresql}\n"
        assertThat(validate(source, thing + storage("db#thing"))).isEmpty()
        val sources =
            Sources.load(
                ContractValidator.validate(contract).contract!!,
                LocatedDocument.read(contract),
                Diagnostics(),
            )
        assertThat(
            sources.table("db#thing")!!.columns.keys,
        ).containsExactly("public_id", "first", "third", "tenth", "earlier", "later")
    }

    @Test
    fun `a migrations path that matches no file is RIALTO-S001`() {
        val diagnostic =
            validate(
                "  db: {kind: sql, path: 'db/*.sql', dialect: postgresql}\n",
            ).single()
        assertThat(diagnostic.code).isEqualTo(Code.SOURCE_UNREADABLE)
        assertThat(diagnostic.message).isEqualTo("Source 'db' matches no migration: db/*.sql")
        assertThat(diagnostic.location.pointer).isEqualTo("/sources/db/path")
    }

    @Test
    fun `a successor that is not a declared source is RIALTO-K004`() {
        file("api.json", """{"openapi": "3.1.0"}""")
        val diagnostic =
            validate(
                "  api: {kind: openapi, path: api.json, version: 1.0.0, status: deprecated, deprecated: '2026-01-01', successor: next}\n",
            ).single()
        assertThat(diagnostic.code).isEqualTo(Code.UNRESOLVED_REFERENCE)
        assertThat(diagnostic.message).isEqualTo("Unknown source 'next'")
        assertThat(diagnostic.location.pointer).isEqualTo("/sources/api/successor")
    }
}
