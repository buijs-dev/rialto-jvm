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

import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkObject
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.writeText

/** Loading a document with positions, and the steps before the checks: K001, K003 and K002. */
class LoadingTest {
    @TempDir
    lateinit var work: Path

    private fun write(
        name: String,
        text: String,
    ): Path = work.resolve(name).also { it.writeText(text) }

    private fun validate(
        name: String,
        text: String,
    ) = ContractValidator.validate(write(name, text))

    @Test
    fun `YAML keeps the line and column of every key and list element`() {
        val file = Path.of("doc.yaml")
        val document = LocatedDocument.parse(file, "a:\n  b: [x, y]\n  \"c/d~e\": 1\n")
        assertThat(document.at("/a/b/1")!!.asString()).isEqualTo("y")
        assertThat(document.positions[""]).isEqualTo(Position(file, 1, 1))
        assertThat(document.positions["/a"]).isEqualTo(Position(file, 1, 1))
        assertThat(document.positions["/a/b"]).isEqualTo(Position(file, 2, 3))
        assertThat(document.positions["/a/b/1"]).isEqualTo(Position(file, 2, 10))
        assertThat(document.positions["/a/c~1d~0e"]).isEqualTo(Position(file, 3, 3))
    }

    @Test
    fun `JSON is read by its extension, with positions and every kind of value`() {
        val file =
            write(
                "doc.JSON",
                "{\n  \"types\": {\n    \"A\": {\"kind\": \"enum\", \"members\": [\"a\"]}\n  },\n" +
                    "  \"n\": 12345678901234567890, \"f\": 1.5, \"t\": true, \"no\": false, \"z\": null\n}\n",
            )
        val document = LocatedDocument.read(file)
        assertThat(document.at("/types/A/members/0")!!.asString()).isEqualTo("a")
        assertThat(document.locate("/types/A").line).isEqualTo(3)
        assertThat(document.locate("/types/A").column).isEqualTo(5)
        assertThat(
            document.at("/n")!!.bigIntegerValue().toString(),
        ).isEqualTo("12345678901234567890")
        assertThat(document.at("/f")!!.decimalValue().toString()).isEqualTo("1.5")
        assertThat(document.at("/t")!!.booleanValue()).isTrue()
        assertThat(document.at("/no")!!.booleanValue()).isFalse()
        assertThat(document.at("/z")!!.isNull).isTrue()
        assertThat(document.at("/missing")).isNull()
        assertThat(document.at("types/A")).isNull()
        assertThat(document.at("")).isEqualTo(document.root)
    }

    @Test
    fun `YAML is read as YAML 1_2 with the core schema`() {
        val document =
            LocatedDocument.parse(
                Path.of("doc.yaml"),
                "tilde: ~\nnull: Null\nyes: yes\nno: no\ntrue: True\n" +
                    "zero: 0444\noctal: 0o14\nhex: 0x1F\nunder: 1_000\nplus: +12\nquoted: '~'\n",
            )

        assertThat(document.at("/tilde")!!.isNull).isTrue()
        assertThat(document.at("/null")!!.isNull).isTrue()
        assertThat(document.at("/yes")!!.stringValue()).isEqualTo("yes")
        assertThat(document.at("/no")!!.stringValue()).isEqualTo("no")
        assertThat(document.at("/true")!!.booleanValue()).isTrue()
        assertThat(document.at("/zero")!!.intValue()).isEqualTo(444)
        assertThat(document.at("/octal")!!.intValue()).isEqualTo(12)
        assertThat(document.at("/hex")!!.intValue()).isEqualTo(31)
        assertThat(document.at("/under")!!.stringValue()).isEqualTo("1_000")
        assertThat(document.at("/plus")!!.intValue()).isEqualTo(12)
        assertThat(document.at("/quoted")!!.stringValue()).isEqualTo("~")
    }

    @Test
    fun `a pointer without a position is located at its nearest ancestor, or at the file`() {
        val file = Path.of("doc.yaml")
        val location = LocatedDocument.parse(file, "a:\n  b: 1\n").locate("/a/b/missing/deeper")
        assertThat(location).isEqualTo(Location(file, 2, 3, "/a/b/missing/deeper"))

        val empty = LocatedDocument.parse(file, "")
        assertThat(empty.root.isObject).isTrue()
        assertThat(empty.locate("/x")).isEqualTo(Location(file, pointer = "/x"))
    }

    @Test
    fun `a key that is used twice in one mapping is RIALTO-K001, in YAML and in JSON`() {
        val yaml =
            validate(
                "c.yaml",
                "rialto: '1.0.0'\ninfo: {name: x, version: 1.0.0}\nrialto: '2.0.0'\n",
            )
        assertThat(yaml.diagnostics.single().code).isEqualTo(Code.UNREADABLE)
        assertThat(yaml.diagnostics.single().message)
            .isEqualTo("Duplicate key 'rialto' at line 3, column 1")

        val json =
            validate(
                "c.json",
                """{"types": {"A": {"kind": "value"}, "A": {"kind": "value"}}}""",
            )
        assertThat(json.diagnostics.single().code).isEqualTo(Code.UNREADABLE)
        assertThat(json.diagnostics.single().message)
            .startsWith("Duplicate key 'A' at line 1, column ")
    }

    @Test
    fun `a file with more than one document is RIALTO-K001`() {
        val contract = "rialto: '1.0.0'\ninfo: {name: x, version: 1.0.0}\n"

        val second = validate("c.yaml", "$contract---\nb: 2\n")
        assertThat(second.diagnostics.single().code).isEqualTo(Code.UNREADABLE)
        assertThat(second.diagnostics.single().message)
            .startsWith("Only one document is expected, but more follows at line ")

        val trailing = validate("c.json", """{"rialto": "1.0.0"} {"more": 1}""")
        assertThat(trailing.diagnostics.single().code).isEqualTo(Code.UNREADABLE)

        // A document start or end marker alone is no second document.
        assertThat(validate("c.yaml", "---\n$contract...\n# done\n").diagnostics).isEmpty()
    }

    @Test
    fun `pointer segments escape tilde and slash`() {
        assertThat(LocatedDocument.segment("a/b~c")).isEqualTo("a~1b~0c")
    }

    @Test
    fun `a document that cannot be parsed or is no mapping is RIALTO-K001`() {
        listOf(
            "rialto: [\n" to "",
            "- a\n- b\n" to "a list",
            "just text\n" to "a single value",
            "~\n" to "null",
        ).forEach { (text, found) ->
            val diagnostic = validate("c.yaml", text).diagnostics.single()
            assertThat(diagnostic.code).describedAs(text).isEqualTo(Code.UNREADABLE)
            assertThat(
                diagnostic.location.file.fileName
                    .toString(),
            ).isEqualTo("c.yaml")
            assertThat(diagnostic.message).contains(found)
        }

        val list = LocatedDocument.readMapping(write("l.yaml", "- a\n"))
        assertThat(list).isInstanceOf(Outcome.Nok::class.java)
        assertThat(list.diagnostics.single().message)
            .isEqualTo("The document is not a mapping but a list")
    }

    @Test
    fun `a contract file that does not exist is RIALTO-K001`() {
        val result = ContractValidator.validate(work.resolve("missing.yaml"))
        assertThat(result).isInstanceOf(Outcome.Nok::class.java)
        assertThat(result.diagnostics.single().code).isEqualTo(Code.UNREADABLE)
    }

    @Test
    fun `another or a missing format version is RIALTO-K003 at rialto, with the supported version as hint`() {
        val other =
            validate(
                "c.yaml",
                "rialto: '2.0.0'\ninfo: {name: x, version: 1.0.0}\n",
            ).diagnostics.single()
        assertThat(other.code).isEqualTo(Code.UNSUPPORTED_VERSION)
        assertThat(other.location.pointer).isEqualTo("/rialto")
        assertThat(other.location.line).isEqualTo(1)
        assertThat(other.hint).isEqualTo("This tool reads format 1.0.0")
        assertThat(other.toString()).endsWith("(K1.1)\n  hint: This tool reads format 1.0.0")

        val missing = validate("m.yaml", "info: {name: x, version: 1.0.0}\n").diagnostics.single()
        assertThat(missing.code).isEqualTo(Code.UNSUPPORTED_VERSION)
        assertThat(missing.message).isEqualTo("Contract format '' is not supported")
    }

    @Test
    fun `a JSON Schema finding is RIALTO-K002 at its pointer, and at the root for a missing key`() {
        val unknown =
            validate(
                "c.yaml",
                "rialto: '1.0.0'\ninfo: {name: x, version: 1.0.0}\ntypes:\n  A: {kind: other}\n",
            ).diagnostics
        assertThat(unknown).isNotEmpty().allMatch { it.code == Code.SCHEMA }

        assertThat(unknown.map { it.location.pointer }).contains("/types/A")

        val root = validate("r.yaml", "rialto: '1.0.0'\n").diagnostics.single()
        assertThat(root.code).isEqualTo(Code.SCHEMA)
        assertThat(root.location.pointer).isEmpty()
        assertThat(root.message).contains("info")
    }

    @Test
    fun `an integer beyond 32 bits is RIALTO-K002 from the JSON Schema, at its pointer`() {
        val diagnostics =
            validate(
                "c.yaml",
                "rialto: '1.0.0'\ninfo: {name: x, version: 1.0.0}\n" +
                    "types:\n  T:\n    kind: value\n    properties:\n      a: {type: string, maxLength: 99999999999999}\n",
            ).diagnostics
        assertThat(diagnostics).isNotEmpty().allMatch { it.code == Code.SCHEMA }

        assertThat(
            diagnostics.map { it.location.pointer },
        ).contains("/types/T/properties/a/maxLength")
    }

    @Test
    fun `a document that does not fit the model is RIALTO-K002 at the element it rejects, not a crash`() {
        // The JSON Schema rejects this document; the model step guards a gap between the schema and the model.
        val file = work.resolve("c.yaml")
        val document =
            LocatedDocument.parse(
                file,
                "rialto: '1.0.0'\ninfo: {name: x, version: 1.0.0}\n" +
                    "types:\n  T:\n    kind: value\n    properties:\n      a: {type: string, maxLength: 99999999999999}\n",
            )
        val outcome = ContractValidator.model(document)
        assertThat(outcome).isInstanceOf(Outcome.Nok::class.java)
        val diagnostic = outcome.diagnostics.single()
        assertThat(diagnostic.code).isEqualTo(Code.SCHEMA)
        assertThat(diagnostic.location).isEqualTo(Location(file, 4, 3, "/types/T"))
        assertThat(diagnostic.message)
            .startsWith(
                "the contract passes the JSON Schema but does not fit the contract model at /types/T: ",
            ).contains("maxLength")
    }

    @Test
    fun `a contract in JSON is validated like one in YAML`() {
        val result =
            validate(
                "c.json",
                """{"rialto": "1.0.0", "info": {"name": "x", "version": "1.0.0"}, "types": {"A": {"kind": "value", "properties": {"b": {"type": "Missing"}}}}}""",
            )
        val diagnostic = result.diagnostics.single()
        assertThat(diagnostic.code).isEqualTo(Code.UNRESOLVED_REFERENCE)
        assertThat(diagnostic.location.line).isEqualTo(1)
        assertThat(result).isInstanceOf(Outcome.Nok::class.java)
    }

    @Test
    fun `a diagnostic prints its severity, code, place, requirement and hint`() {
        val file = Path.of("dir/c.yaml")
        val diagnostics = Diagnostics()
        diagnostics.warning(Code.SCHEMA, "careful", Location(file), "look again")
        assertThat(diagnostics.hasErrors).isFalse()
        diagnostics.add(
            Diagnostic(
                Severity.ERROR,
                Code.UNREADABLE,
                "broken",
                Location(file, 3),
                requirementOverride = "K9.9",
            ),
        )
        assertThat(diagnostics.hasErrors).isTrue()

        val (warning, error) = diagnostics.all
        assertThat(
            warning.toString(),
        ).isEqualTo("warning[RIALTO-K002] c.yaml: careful (K1.7)\n  hint: look again")
        assertThat(error.toString()).isEqualTo("error[RIALTO-K001] c.yaml:3:1: broken (K9.9)")
        assertThat(error.requirement).isEqualTo("K9.9")
        assertThat(Location(file, 2, 7, "/a").toString()).isEqualTo("c.yaml:2:7 #/a")
    }

    @Test
    fun `an outcome is Ok without errors, warnings allowed, and Nok with at least one error`() {
        val warning = Diagnostic(Severity.WARNING, Code.SCHEMA, "w", Location(Path.of("c.yaml")))
        val error = Diagnostic.error(Code.SCHEMA, "e", Location(Path.of("c.yaml")))

        assertThat(Outcome.Ok("value").diagnostics).isEmpty()
        assertThat(Outcome.Ok("value", listOf(warning)).value).isEqualTo("value")
        assertThat(Outcome.Nok(listOf(warning, error)).diagnostics).containsExactly(warning, error)

        assertThatThrownBy { Outcome.Ok("value", listOf(warning, error)) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("An outcome with an error is not Ok")
        assertThatThrownBy { Outcome.Nok(listOf(warning)) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("An outcome without an error is not Nok")
    }

    @Test
    fun `an outcome of a value and its findings is Nok as soon as one finding is an error`() {
        val warning = Diagnostic(Severity.WARNING, Code.SCHEMA, "w", Location(Path.of("c.yaml")))
        val error = Diagnostic.error(Code.SCHEMA, "e", Location(Path.of("c.yaml")))

        assertThat(Outcome.of("value", listOf(warning)))
            .isEqualTo(Outcome.Ok("value", listOf(warning)))
        assertThat(Outcome.of("value", listOf(warning, error)))
            .isEqualTo(Outcome.Nok(listOf(warning, error)))
    }

    @Test
    fun `a warning has no hint unless it is given one`() {
        val diagnostics = Diagnostics()
        diagnostics.warning(Code.SCHEMA, "careful", Location(Path.of("a.yaml")))

        assertThat(diagnostics.all.single()).matches {
            it.hint == null &&
                it.severity == Severity.WARNING
        }

        assertThat(diagnostics.hasErrors).isFalse()
        assertThat(
            diagnostics.all.single().toString(),
        ).isEqualTo("warning[RIALTO-K002] a.yaml: careful (K1.7)")
    }

    @Test
    fun `a location shows the line, the column or 1, and the pointer it knows`() {
        assertThat(Location(Path.of("/x/a.yaml")).toString()).isEqualTo("a.yaml")
        assertThat(Location(Path.of("a.yaml"), 3).toString()).isEqualTo("a.yaml:3:1")
        assertThat(
            Location(Path.of("a.yaml"), 3, 7, "/info").toString(),
        ).isEqualTo("a.yaml:3:7 #/info")
    }

    @Test
    fun `every code has an id, a requirement and a summary`() {
        assertThat(Code.entries).allMatch {
            it.id.startsWith("RIALTO-") && it.summary.isNotBlank()
        }

        assertThat(Severity.entries.map { it.label }).containsExactly("error", "warning")
    }

    @Test
    fun `a contract that cannot be read without a reason is reported as one that cannot be read`() {
        mockkObject(LocatedDocument.Companion)
        try {
            every { LocatedDocument.read(any<Path>()) } throws IllegalStateException()

            val outcome = LocatedDocument.readMapping(Path.of("a.yaml"))
            assertThat(outcome).isInstanceOf(Outcome.Nok::class.java)
            assertThat(outcome.diagnostics.single().message).isEqualTo("cannot be read")
        } finally {
            unmockkObject(LocatedDocument.Companion)
        }
    }
}
