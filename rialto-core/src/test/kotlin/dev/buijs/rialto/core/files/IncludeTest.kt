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

package dev.buijs.rialto.core.files

import dev.buijs.rialto.core.diagnostics.Code
import dev.buijs.rialto.core.diagnostics.Outcome
import dev.buijs.rialto.core.diagnostics.valueOrFail
import dev.buijs.rialto.core.document.LocatedDocument
import dev.buijs.rialto.core.utilities.Glob
import dev.buijs.rialto.core.validation.ContractValidator
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
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
        // given
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

        // when
        val read = ContractFiles.read(contract)

        // then
        assertThat(read.diagnostics).isEmpty()
        val document = read.valueOrFail()
        val sections =
            document.root
                .propertyNames()
                .asSequence()
                .toList()
        assertThat(sections).containsExactly("rialto", "info", "types", "mappings")
        val types =
            document.root
                .path("types")
                .propertyNames()
                .asSequence()
                .toList()
        assertThat(types).containsExactly("Thing", "Size")
        // parts/c sorts before parts/deeper/b.
        val directions =
            document.root
                .path("mappings")
                .values()
                .map { it.path("direction").asString() }
        assertThat(directions).containsExactly("both", "toTarget")
        val mapping = document.locate("/mappings/1/direction")
        assertThat(mapping.file.fileName.toString()).isEqualTo("b.rialto.yaml")
        assertThat(mapping.line).isEqualTo(5)
        assertThat(document.locate("/types/Size").file).hasFileName("b.rialto.yaml")
        assertThat(document.locate("/info").file).isEqualTo(contract)
        assertThat(ContractValidator.validate(contract)).isInstanceOf(Outcome.Ok::class.java)
    }

    @Test
    fun `lists its fragments for build tools, and none without include or when the root cannot be read`() {
        // given
        write(
            "${ROOT}include: [parts/*.yaml]\n",
            mapOf(
                "parts/b.yaml" to FRAGMENT,
                "parts/a.yaml" to FRAGMENT,
            ),
        )

        // when
        val fragments = ContractFiles.fragments(contract).map { it.fileName.toString() }

        // then
        assertThat(fragments).containsExactly("a.yaml", "b.yaml")

        // given
        write(ROOT)

        // expect
        assertThat(ContractFiles.fragments(contract)).isEmpty()

        // given
        write("include: [")

        // expect
        assertThat(ContractFiles.fragments(contract)).isEmpty()
    }

    @Test
    fun `a root without include is read as it is`() {
        // given
        write("$ROOT$THING")

        // when
        val document = ContractFiles.read(contract).valueOrFail()

        // then
        assertThat(document.locate("/types/Thing").line).isEqualTo(4)
        assertThat(document.locate("/types/Thing").column).isEqualTo(3)
    }

    @ParameterizedTest(name = "include: {0}")
    @CsvSource(
        delimiter = '|',
        value = [
            "\"parts/*.yaml\"   | /include",
            "[]                | /include",
            "[42]              | /include/0",
            "[a.yaml, a.yaml]  | /include",
        ],
    )
    fun `an include that does not conform to the JSON Schema is RIALTO-K002`(
        include: String,
        pointer: String,
    ) {
        // given
        write("${ROOT}include: $include\n", mapOf("a.yaml" to "$FRAGMENT$THING"))

        // expect
        assertThat(findings().map { it[0] to it[2] }).containsExactly("RIALTO-K002" to pointer)
    }

    @Test
    fun `a path that matches no file is RIALTO-K007, at its entry`() {
        // given
        write(
            "${ROOT}include: [parts/*.yaml, missing/*.yaml, \"**/none.yaml\"]\n",
            mapOf("parts/a.yaml" to "$FRAGMENT$THING"),
        )

        // expect
        assertThat(findings()).containsExactly(
            listOf("RIALTO-K007", "contract.rialto.yaml", "/include/1", 3),
            listOf("RIALTO-K007", "contract.rialto.yaml", "/include/2", 3),
        )
    }

    @Test
    fun `a final double star includes every file below its directory`() {
        // given
        write(
            "${ROOT}include: [\"parts/**\"]\n",
            mapOf(
                "parts/a.yaml" to "$FRAGMENT$THING",
                "parts/x/b.yaml" to "${FRAGMENT}navigation: {home: Home}\n",
            ),
        )

        // when
        val fragments = ContractFiles.fragments(contract).map { work.relativize(it).joinToString("/") }

        // then
        assertThat(fragments).containsExactly("parts/a.yaml", "parts/x/b.yaml")
    }

    @Test
    fun `the root never includes itself`() {
        // given
        write("${ROOT}include: [\"*.yaml\"]\n")

        // expect
        assertThat(findings().map { it[0] }).containsExactly("RIALTO-K007")
    }

    @Test
    fun `a fragment that declares info, sources or include is RIALTO-K008, once per key`() {
        // given
        write(
            "${ROOT}include: [parts/*.yaml]\n",
            mapOf(
                "parts/a.yaml" to
                    "${FRAGMENT}info: {name: y, version: 1.0.0}\n" +
                    "sources:\n  api: {kind: openapi, path: api.json}\n" +
                    "include: [other.yaml]\n$THING",
            ),
        )

        // expect
        assertThat(findings()).containsExactly(
            listOf("RIALTO-K008", "parts/a.yaml", "/info", 2),
            listOf("RIALTO-K008", "parts/a.yaml", "/sources", 3),
            listOf("RIALTO-K008", "parts/a.yaml", "/include", 5),
        )
    }

    @Test
    fun `a section of the root with the wrong type is still reported, also when a fragment has that section`() {
        // given
        val mapping = "mappings:\n- {source: Thing, target: Thing, direction: both}\n"
        write(
            "${ROOT}types: 42\nmappings: none\ninclude: [parts/*.yaml]\n",
            mapOf("parts/a.yaml" to "$FRAGMENT$THING$mapping"),
        )

        // expect
        assertThat(findings()).containsExactly(
            listOf("RIALTO-K002", "contract.rialto.yaml", "/mappings", 4),
            listOf("RIALTO-K002", "contract.rialto.yaml", "/types", 3),
        )
    }

    @Test
    fun `a section that belongs in one file appears in two - RIALTO-K009, naming the first file`() {
        // given
        val navigation = "navigation: {home: Home}\n"
        write(
            "${ROOT}include: [parts/*.yaml]\n",
            mapOf(
                "parts/a.yaml" to "$FRAGMENT$navigation",
                "parts/b.yaml" to "$FRAGMENT$navigation",
            ),
        )

        // when
        val diagnostic = validate().single()

        // then
        assertThat(diagnostic.code).isEqualTo(Code.SECTION_TWICE)
        assertThat(diagnostic.message).contains("parts/a.yaml")
        assertThat(diagnostic.location.file).hasFileName("b.yaml")
        assertThat(diagnostic.location.pointer).isEqualTo("/navigation")

        // given
        write("$ROOT${navigation}include: [parts/a.yaml]\n")

        // expect
        assertThat(validate().single().message).contains("contract.rialto.yaml")
    }

    @Test
    fun `a name declared in two files is RIALTO-K005, in the later file`() {
        // given
        write("$ROOT${THING}include: [parts/*.yaml]\n", mapOf("parts/a.yaml" to "$FRAGMENT$THING"))

        // when
        val diagnostic = validate().single()

        // then
        assertThat(diagnostic.code).isEqualTo(Code.DUPLICATE_NAME)
        assertThat(diagnostic.message).isEqualTo("'Thing' is already declared under types in contract.rialto.yaml")
        assertThat(diagnostic.location.file).hasFileName("a.yaml")
        assertThat(diagnostic.location.pointer).isEqualTo("/types/Thing")
    }

    @Test
    fun `a fragment of another format version is RIALTO-K003, and one without a version too`() {
        // given
        write(
            "${ROOT}include: [parts/*.yaml]\n",
            mapOf("parts/a.yaml" to "rialto: \"2.0.0\"\n$THING", "parts/b.yaml" to THING),
        )

        // expect
        assertThat(findings()).containsExactly(
            listOf("RIALTO-K003", "parts/a.yaml", "/rialto", 1),
            listOf("RIALTO-K003", "parts/b.yaml", "/rialto", 1),
        )
        assertThat(validate().first().message).isEqualTo("Fragment has format '2.0.0', the root '1.0.0'")
    }

    @Test
    fun `a fragment that does not conform to the JSON Schema is RIALTO-K002, in the fragment`() {
        // given
        write(
            "${ROOT}include: [parts/*.yaml]\n",
            mapOf("parts/a.yaml" to "${FRAGMENT}types:\n  Size: {kind: enum}\n"),
        )

        // when
        val found = findings()

        // then
        assertThat(found).isNotEmpty().allMatch { it[0] == "RIALTO-K002" && it[1] == "parts/a.yaml" }
    }

    @Test
    fun `a fragment that cannot be read or is no mapping is RIALTO-K001`() {
        // given
        write(
            "${ROOT}include: [parts/*.yaml]\n",
            mapOf(
                "parts/a.yaml" to "types: [\n",
                "parts/b.yaml" to "- a\n",
            ),
        )

        // expect
        assertThat(findings().map { it[0] to it[1] }).containsExactly(
            "RIALTO-K001" to "parts/a.yaml",
            "RIALTO-K001" to "parts/b.yaml",
        )
    }

    @Test
    fun `a finding of a later check names the fragment it is in (K5_5)`() {
        // given
        write(
            "${ROOT}include: [parts/*.yaml]\n",
            mapOf(
                "parts/a.yaml" to
                    "${FRAGMENT}types:\n  A: {kind: value, properties: {b: {type: Missing}}}\n",
            ),
        )

        // expect
        assertThat(findings()).containsExactly(listOf("RIALTO-K004", "parts/a.yaml", "/types/A/properties/b/type", 3))
    }

    @Test
    fun `bundle writes the bundled form, with local source paths relative to the new place`() {
        // given
        work.resolve("api.json").writeText("""{"openapi": "3.1.0", "components": {"schemas": {}}}""")
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

        // when
        val outcome = ContractFiles.bundle(contract, output)

        // then
        assertThat(outcome).isEqualTo(Outcome.Ok(output))
        val bundled = LocatedDocument.read(output).root
        assertThat(bundled.has("include")).isFalse()
        val types =
            bundled
                .path("types")
                .propertyNames()
                .asSequence()
                .toList()
        assertThat(types).containsExactly("Thing")
        assertThat(bundled.at("/sources/api/path").asString()).isEqualTo("../../api.json")
        assertThat(bundled.at("/sources/remote/path").asString()).isEqualTo("https://x.example/api.json")
    }

    @Test
    fun `bundle writes a contract without sources or include as it is`() {
        // given
        write("$ROOT$THING")
        val output = work.resolve("copy.rialto.yaml")

        // when
        val outcome = ContractFiles.bundle(contract, output)

        // then
        assertThat(outcome).isEqualTo(Outcome.Ok(output))
        assertThat(LocatedDocument.read(output).root).isEqualTo(LocatedDocument.read(contract).root)
    }

    @Test
    fun `bundle writes nothing for a bundled form of another version or not valid against the schema (K5_6)`() {
        // given
        val output = work.resolve("out.rialto.yaml")

        // when
        write("$ROOT${THING}bogus: 1\n")
        val invalid = ContractFiles.bundle(contract, output)
        write("$ROOT$THING".replace("rialto: \"1.0.0\"", "rialto: \"0.9.0\""))
        val version = ContractFiles.bundle(contract, output)

        // then
        assertThat(invalid).isInstanceOf(Outcome.Nok::class.java)
        assertThat(invalid.diagnostics.map { it.code }).containsExactly(Code.SCHEMA)
        assertThat(version).isInstanceOf(Outcome.Nok::class.java)
        assertThat(version.diagnostics.map { it.code }).containsExactly(Code.UNSUPPORTED_VERSION)
        assertThat(output.exists()).isFalse()
    }

    @Test
    fun `bundle writes nothing when the contract has errors or cannot be read`() {
        // given
        val output = work.resolve("out.rialto.yaml")
        write("${ROOT}include: [missing/*.yaml]\n")

        // when
        val noMatch = ContractFiles.bundle(contract, output)
        val unreadable = ContractFiles.bundle(work.resolve("missing.yaml"), output)

        // then
        assertThat(noMatch).isInstanceOf(Outcome.Nok::class.java)
        assertThat(noMatch.diagnostics.single().code).isEqualTo(Code.INCLUDE_NO_MATCH)
        assertThat(unreadable).isInstanceOf(Outcome.Nok::class.java)
        assertThat(unreadable.diagnostics.single().code).isEqualTo(Code.UNREADABLE)
        assertThat(output.exists()).isFalse()
    }

    @ParameterizedTest(name = "{0} matches {1}: {2}")
    @CsvSource(
        delimiter = '|',
        value = [
            "*.yaml         | a.yaml       | true",
            "*.yaml         | x/a.yaml     | false",
            "**/a.yaml      | a.yaml       | true",
            "**/a.yaml      | x/y/a.yaml   | true",
            "x/**           | x/a.yaml     | true",
            "x/**           | x/a/b.yaml   | true",
            "x/**           | y/a.yaml     | false",
            "**             | a/b.yaml     | true",
            "a/**/b/*.yaml  | a/b/c.yaml   | true",
            "a+b(c).yaml    | a+b(c).yaml  | true",
            "a?.yaml        | ab.yaml      | false",
            "[ab].yaml      | a.yaml       | false",
        ],
    )
    fun `include patterns - star within a segment, double star over any number of segments, the rest literal`(
        pattern: String,
        path: String,
        matches: Boolean,
    ) {
        // expect
        assertThat(Glob.regex(pattern).matches(path)).isEqualTo(matches)
    }

    @Test
    fun `the inputs of a build are the root, its lock, its fragments and its local sources`() {
        // given
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

        // when
        val inputs = ContractFiles.inputs(contract).map { work.relativize(it.toAbsolutePath()).joinToString("/") }

        // then
        assertThat(inputs).containsExactly(
            "contract.rialto.yaml",
            "contract.rialto.lock",
            "parts/a.yaml",
            "api.json",
            "db/V1__a.sql",
            "db/V2__b.sql",
        )
    }

    @Test
    fun `a root that does not exist is its only input, and one that cannot be read comes with its lock`() {
        // expect
        assertThat(ContractFiles.inputs(contract)).containsExactly(contract)

        // given
        write("- a list\n")

        // expect
        assertThat(ContractFiles.inputs(contract)).containsExactly(contract, work.resolve("contract.rialto.lock"))
    }

    @Test
    fun `an include never reaches outside the root's directory`() {
        // given
        val root = work.resolve("root").createDirectories().resolve("contract.rialto.yaml")
        root.writeText("${ROOT}include: [../outside/*.yaml]\n")
        work
            .resolve("outside")
            .createDirectories()
            .resolve("a.yaml")
            .writeText("$FRAGMENT$THING")

        // when
        val codes = ContractValidator.validate(root).diagnostics.map { it.code }

        // then
        assertThat(codes).containsExactly(Code.INCLUDE_NO_MATCH)
    }

    @Test
    fun `mappings of the root come first, then those of the fragments`() {
        // given
        val mapping = "mappings:\n- {source: Thing, target: Thing, direction: both}\n"
        write(
            "$ROOT$THING${mapping}include: [parts/*.yaml]\n",
            mapOf("parts/a.yaml" to "$FRAGMENT$mapping"),
        )

        // when
        val read = ContractFiles.read(contract)

        // then
        assertThat(read.diagnostics).isEmpty()
        val document = read.valueOrFail()
        assertThat(document.root.path("mappings").size()).isEqualTo(2)
        assertThat(document.locate("/mappings/1").file).isEqualTo(work.resolve("parts/a.yaml"))
    }

    private companion object {
        const val ROOT = "rialto: \"1.0.0\"\ninfo: {name: x, version: 1.0.0}\n"
        const val FRAGMENT = "rialto: \"1.0.0\"\n"
        const val THING =
            "types:\n  Thing: {kind: entity, identity: [id], properties: {id: {type: uuid}}}\n"
    }
}
