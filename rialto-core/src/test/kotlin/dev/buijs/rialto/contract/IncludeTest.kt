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
import kotlin.io.path.exists
import kotlin.io.path.writeText

/** A contract over several files (K5): include, the merge of the fragments, and the bundled form. */
class IncludeTest {
    @TempDir
    lateinit var work: Path

    private val contract: Path get() = work.resolve("contract.rialto.yaml")

    /** Writes the root [text] and the [files] next to it. */
    private fun write(
        text: String,
        files: Map<String, String> = emptyMap(),
    ) {
        contract.writeText(text)
        files.forEach { (name, content) ->
            work.resolve(name).also { it.parent.createDirectories() }.writeText(content)
        }
    }

    private fun validate() = ContractValidator.validate(contract).diagnostics

    /** The code, the file relative to the work directory, the pointer and the line of each finding. */
    private fun findings() =
        validate().map {
            listOf(
                it.code.id,
                work.relativize(it.location.file).joinToString("/"),
                it.location.pointer,
                it.location.line,
            )
        }

    @Test
    fun `merges keyed sections and mappings from every fragment, in path order`() {
        write(
            "${ROOT}include: [\"parts/**/*.rialto.yaml\"]\n",
            mapOf(
                "parts/a.rialto.yaml" to "$FRAGMENT$THING",
                "parts/deeper/b.rialto.yaml" to
                    "${FRAGMENT}types:\n  Size: {kind: enum, members: [small]}\n" +
                    "mappings:\n  - {source: Thing, target: Thing, direction: toTarget}\n",
                "parts/c.rialto.yaml" to
                    "${FRAGMENT}mappings:\n  - {source: Thing, target: Thing, direction: both}\n",
                "parts/ignored.yaml" to "not: [a fragment\n",
            ),
        )
        val read = ContractFiles.read(contract)
        assertThat(read.diagnostics).isEmpty()
        val document = read.valueOrFail()
        assertThat(
            document.root
                .propertyNames()
                .asSequence()
                .toList(),
        ).containsExactly("rialto", "info", "types", "mappings")
        assertThat(
            document.root
                .path("types")
                .propertyNames()
                .asSequence()
                .toList(),
        ).containsExactly("Thing", "Size")
        // parts/c sorts before parts/deeper/b.
        assertThat(
            document.root
                .path("mappings")
                .values()
                .map { it.path("direction").asString() },
        ).containsExactly("both", "toTarget")
        val mapping = document.locate("/mappings/1/direction")
        assertThat(mapping.file.fileName.toString()).isEqualTo("b.rialto.yaml")
        assertThat(mapping.line).isEqualTo(5)
        assertThat(
            document
                .locate("/types/Size")
                .file.fileName
                .toString(),
        ).isEqualTo("b.rialto.yaml")
        assertThat(document.locate("/info").file).isEqualTo(contract)
        assertThat(ContractValidator.validate(contract)).isInstanceOf(Outcome.Ok::class.java)
    }

    @Test
    fun `lists its fragments for build tools, and none without include or when the root cannot be read`() {
        write(
            "${ROOT}include: [parts/*.yaml]\n",
            mapOf(
                "parts/b.yaml" to FRAGMENT,
                "parts/a.yaml" to FRAGMENT,
            ),
        )
        assertThat(
            ContractFiles.fragments(contract).map { it.fileName.toString() },
        ).containsExactly("a.yaml", "b.yaml")
        write(ROOT)
        assertThat(ContractFiles.fragments(contract)).isEmpty()
        write("include: [")
        assertThat(ContractFiles.fragments(contract)).isEmpty()
    }

    @Test
    fun `a root without include is read as it is`() {
        write("$ROOT$THING")
        val document = ContractFiles.read(contract).valueOrFail()
        assertThat(document.locate("/types/Thing").line).isEqualTo(4)
        assertThat(document.locate("/types/Thing").column).isEqualTo(3)
    }

    @Test
    fun `an include that does not conform to the JSON Schema is RIALTO-K002`() {
        listOf(
            "\"parts/*.yaml\"" to "/include",
            "[]" to "/include",
            "[42]" to "/include/0",
            "[a.yaml, a.yaml]" to "/include",
        ).forEach { (include, pointer) ->
            write("${ROOT}include: $include\n", mapOf("a.yaml" to "$FRAGMENT$THING"))
            assertThat(
                findings().map { it[0] to it[2] },
            ).describedAs(include).containsExactly("RIALTO-K002" to pointer)
        }
    }

    @Test
    fun `a path that matches no file is RIALTO-K007, at its entry`() {
        write(
            "${ROOT}include: [parts/*.yaml, missing/*.yaml, \"**/none.yaml\"]\n",
            mapOf("parts/a.yaml" to "$FRAGMENT$THING"),
        )
        assertThat(findings()).containsExactly(
            listOf("RIALTO-K007", "contract.rialto.yaml", "/include/1", 3),
            listOf("RIALTO-K007", "contract.rialto.yaml", "/include/2", 3),
        )
    }

    @Test
    fun `a final double star includes every file below its directory`() {
        write(
            "${ROOT}include: [\"parts/**\"]\n",
            mapOf(
                "parts/a.yaml" to "$FRAGMENT$THING",
                "parts/x/b.yaml" to "${FRAGMENT}navigation: {home: Home}\n",
            ),
        )
        assertThat(ContractFiles.fragments(contract).map { work.relativize(it).joinToString("/") })
            .containsExactly("parts/a.yaml", "parts/x/b.yaml")
    }

    @Test
    fun `the root never includes itself`() {
        write("${ROOT}include: [\"*.yaml\"]\n")
        assertThat(findings().map { it[0] }).containsExactly("RIALTO-K007")
    }

    @Test
    fun `a fragment that declares info, sources or include is RIALTO-K008, once per key`() {
        write(
            "${ROOT}include: [parts/*.yaml]\n",
            mapOf(
                "parts/a.yaml" to
                    "${FRAGMENT}info: {name: y, version: 1.0.0}\n" +
                    "sources:\n  api: {kind: openapi, path: api.json}\n" +
                    "include: [other.yaml]\n$THING",
            ),
        )
        assertThat(findings()).containsExactly(
            listOf("RIALTO-K008", "parts/a.yaml", "/info", 2),
            listOf("RIALTO-K008", "parts/a.yaml", "/sources", 3),
            listOf("RIALTO-K008", "parts/a.yaml", "/include", 5),
        )
    }

    @Test
    fun `a section of the root with the wrong type is still reported, also when a fragment has that section`() {
        val mapping = "mappings:\n- {source: Thing, target: Thing, direction: both}\n"
        write(
            "${ROOT}types: 42\nmappings: none\ninclude: [parts/*.yaml]\n",
            mapOf("parts/a.yaml" to "$FRAGMENT$THING$mapping"),
        )

        assertThat(findings()).containsExactly(
            listOf("RIALTO-K002", "contract.rialto.yaml", "/mappings", 4),
            listOf("RIALTO-K002", "contract.rialto.yaml", "/types", 3),
        )
    }

    @Test
    fun `a section that belongs in one file appears in two - RIALTO-K009, naming the first file`() {
        val navigation = "navigation: {home: Home}\n"
        write(
            "${ROOT}include: [parts/*.yaml]\n",
            mapOf(
                "parts/a.yaml" to "$FRAGMENT$navigation",
                "parts/b.yaml" to "$FRAGMENT$navigation",
            ),
        )
        val diagnostic = validate().single()
        assertThat(diagnostic.code).isEqualTo(Code.SECTION_TWICE)
        assertThat(diagnostic.message).contains("parts/a.yaml")
        assertThat(
            diagnostic.location.file.fileName
                .toString(),
        ).isEqualTo("b.yaml")
        assertThat(diagnostic.location.pointer).isEqualTo("/navigation")

        write("$ROOT${navigation}include: [parts/a.yaml]\n")
        assertThat(validate().single().message).contains("contract.rialto.yaml")
    }

    @Test
    fun `a name declared in two files is RIALTO-K005, in the later file`() {
        write("$ROOT${THING}include: [parts/*.yaml]\n", mapOf("parts/a.yaml" to "$FRAGMENT$THING"))
        val diagnostic = validate().single()
        assertThat(diagnostic.code).isEqualTo(Code.DUPLICATE_NAME)
        assertThat(
            diagnostic.message,
        ).isEqualTo("'Thing' is already declared under types in contract.rialto.yaml")
        assertThat(
            diagnostic.location.file.fileName
                .toString(),
        ).isEqualTo("a.yaml")
        assertThat(diagnostic.location.pointer).isEqualTo("/types/Thing")
    }

    @Test
    fun `a fragment of another format version is RIALTO-K003, and one without a version too`() {
        write(
            "${ROOT}include: [parts/*.yaml]\n",
            mapOf("parts/a.yaml" to "rialto: \"2.0.0\"\n$THING", "parts/b.yaml" to THING),
        )
        assertThat(findings()).containsExactly(
            listOf("RIALTO-K003", "parts/a.yaml", "/rialto", 1),
            listOf("RIALTO-K003", "parts/b.yaml", "/rialto", 1),
        )
        assertThat(
            validate().first().message,
        ).isEqualTo("Fragment has format '2.0.0', the root '1.0.0'")
    }

    @Test
    fun `a fragment that does not conform to the JSON Schema is RIALTO-K002, in the fragment`() {
        write(
            "${ROOT}include: [parts/*.yaml]\n",
            mapOf("parts/a.yaml" to "${FRAGMENT}types:\n  Size: {kind: enum}\n"),
        )
        val found = findings()
        assertThat(
            found,
        ).isNotEmpty().allMatch { it[0] == "RIALTO-K002" && it[1] == "parts/a.yaml" }
    }

    @Test
    fun `a fragment that cannot be read or is no mapping is RIALTO-K001`() {
        write(
            "${ROOT}include: [parts/*.yaml]\n",
            mapOf(
                "parts/a.yaml" to "types: [\n",
                "parts/b.yaml" to "- a\n",
            ),
        )
        assertThat(findings().map { it[0] to it[1] }).containsExactly(
            "RIALTO-K001" to "parts/a.yaml",
            "RIALTO-K001" to "parts/b.yaml",
        )
    }

    @Test
    fun `a finding of a later check names the fragment it is in (K5_5)`() {
        write(
            "${ROOT}include: [parts/*.yaml]\n",
            mapOf(
                "parts/a.yaml" to
                    "${FRAGMENT}types:\n  A: {kind: value, properties: {b: {type: Missing}}}\n",
            ),
        )
        assertThat(
            findings(),
        ).containsExactly(listOf("RIALTO-K004", "parts/a.yaml", "/types/A/properties/b/type", 3))
    }

    @Test
    fun `bundle writes the bundled form, with local source paths relative to the new place`() {
        work
            .resolve(
                "api.json",
            ).writeText("""{"openapi": "3.1.0", "components": {"schemas": {}}}""")
        write(
            ROOT +
                "sources:\n" +
                "  api: {kind: openapi, path: api.json, version: 1.0.0, status: current}\n" +
                "  remote: {kind: openapi, path: \"https://x.example/api.json\",\n" +
                "    version: 1.0.0, status: current}\n" +
                "include: [parts/*.yaml]\n",
            mapOf("parts/a.yaml" to "$FRAGMENT$THING"),
        )
        val output = work.resolve("out/deeper/bundled.rialto.yaml")
        assertThat(ContractFiles.bundle(contract, output)).isEqualTo(Outcome.Ok(output))
        val bundled = LocatedDocument.read(output).root
        assertThat(bundled.has("include")).isFalse()
        assertThat(
            bundled
                .path("types")
                .propertyNames()
                .asSequence()
                .toList(),
        ).containsExactly("Thing")
        assertThat(bundled.at("/sources/api/path").asString()).isEqualTo("../../api.json")
        assertThat(
            bundled.at("/sources/remote/path").asString(),
        ).isEqualTo("https://x.example/api.json")
    }

    @Test
    fun `bundle writes a contract without sources or include as it is`() {
        write("$ROOT$THING")
        val output = work.resolve("copy.rialto.yaml")
        assertThat(ContractFiles.bundle(contract, output)).isEqualTo(Outcome.Ok(output))
        assertThat(LocatedDocument.read(output).root).isEqualTo(LocatedDocument.read(contract).root)
    }

    @Test
    fun `bundle writes nothing when the contract has errors or cannot be read`() {
        val output = work.resolve("out.rialto.yaml")
        write("${ROOT}include: [missing/*.yaml]\n")
        val noMatch = ContractFiles.bundle(contract, output)
        assertThat(noMatch).isInstanceOf(Outcome.Nok::class.java)
        assertThat(noMatch.diagnostics.single().code).isEqualTo(Code.INCLUDE_NO_MATCH)
        val unreadable = ContractFiles.bundle(work.resolve("missing.yaml"), output)
        assertThat(unreadable).isInstanceOf(Outcome.Nok::class.java)
        assertThat(unreadable.diagnostics.single().code).isEqualTo(Code.UNREADABLE)
        assertThat(output.exists()).isFalse()
    }

    @Test
    fun `include patterns - star within a segment, double star over any number of segments, the rest literal`() {
        mapOf(
            ("*.yaml" to "a.yaml") to true,
            ("*.yaml" to "x/a.yaml") to false,
            ("**/a.yaml" to "a.yaml") to true,
            ("**/a.yaml" to "x/y/a.yaml") to true,
            ("x/**" to "x/a.yaml") to true,
            ("x/**" to "x/a/b.yaml") to true,
            ("x/**" to "y/a.yaml") to false,
            ("**" to "a/b.yaml") to true,
            ("a/**/b/*.yaml" to "a/b/c.yaml") to true,
            ("a+b(c).yaml" to "a+b(c).yaml") to true,
            ("a?.yaml" to "ab.yaml") to false,
            ("[ab].yaml" to "a.yaml") to false,
        ).forEach { (input, matches) ->
            assertThat(
                Glob.regex(input.first).matches(input.second),
            ).describedAs("$input").isEqualTo(matches)
        }
    }

    @Test
    fun `the inputs of a build are the root, its lock, its fragments and its local sources`() {
        write(
            ROOT +
                "sources:\n" +
                "  api: {kind: openapi, path: api.json}\n" +
                "  remote: {kind: openapi, path: \"https://x.example/api.json\"}\n" +
                "  db: {kind: sql, path: \"db/*.sql\"}\n" +
                "  odd: {kind: sql}\n" +
                "include: [parts/*.yaml]\n",
            mapOf(
                "parts/a.yaml" to FRAGMENT,
                "api.json" to "{}",
                "db/V2__b.sql" to "",
                "db/V1__a.sql" to "",
            ),
        )

        assertThat(
            ContractFiles.inputs(contract).map {
                work.relativize(it.toAbsolutePath()).joinToString("/")
            },
        ).containsExactly(
            "contract.rialto.yaml",
            "rialto.lock",
            "parts/a.yaml",
            "api.json",
            "db/V1__a.sql",
            "db/V2__b.sql",
        )
    }

    @Test
    fun `a root that does not exist is its only input, and one that cannot be read comes with its lock`() {
        assertThat(ContractFiles.inputs(contract)).containsExactly(contract)
        write("- a list\n")
        assertThat(
            ContractFiles.inputs(contract),
        ).containsExactly(contract, work.resolve("rialto.lock"))
    }

    @Test
    fun `an include never reaches outside the root's directory`() {
        val root = work.resolve("root").createDirectories().resolve("contract.rialto.yaml")
        root.writeText("${ROOT}include: [../outside/*.yaml]\n")
        work
            .resolve("outside")
            .createDirectories()
            .resolve("a.yaml")
            .writeText("$FRAGMENT$THING")

        assertThat(ContractValidator.validate(root).diagnostics.map { it.code })
            .containsExactly(Code.INCLUDE_NO_MATCH)
    }

    @Test
    fun `mappings of the root come first, then those of the fragments`() {
        val mapping = "mappings:\n- {source: Thing, target: Thing, direction: both}\n"
        write(
            "$ROOT$THING${mapping}include: [parts/*.yaml]\n",
            mapOf("parts/a.yaml" to "$FRAGMENT$mapping"),
        )
        val read = ContractFiles.read(contract)
        assertThat(read.diagnostics).isEmpty()
        val document = read.valueOrFail()

        assertThat(document.root.path("mappings").size()).isEqualTo(2)
        assertThat(document.locate("/mappings/1").file).isEqualTo(work.resolve("parts/a.yaml"))
    }

    @Test
    fun `bundle leaves a source it cannot move as it is`() {
        write(ROOT + "sources: {odd: 1, bare: {kind: openapi}}\n")
        val output = work.resolve("out/bundled.rialto.yaml")

        assertThat(ContractFiles.bundle(contract, output)).isEqualTo(Outcome.Ok(output))
        assertThat(
            LocatedDocument.read(output).root.path("sources"),
        ).isEqualTo(LocatedDocument.read(contract).root.path("sources"))
    }

    private companion object {
        const val ROOT = "rialto: \"1.0.0\"\ninfo: {name: x, version: 1.0.0}\n"
        const val FRAGMENT = "rialto: \"1.0.0\"\n"
        const val THING =
            "types:\n  Thing: {kind: entity, identity: [id], properties: {id: {type: uuid}}}\n"
    }
}
