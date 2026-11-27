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

package dev.buijs.rialto.core.model

import dev.buijs.rialto.core.document.ContractMapper
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import tools.jackson.module.kotlin.treeToValue

/**
 * The model read from and written to its document forms, and the derived values the checks of the profiles read:
 * every element that is written in two forms (a name or an object, a string or a list) reads both and writes the
 * form it was read in.
 */
class ModelTest {
    private inline fun <reified T> read(yaml: String): T =
        ContractMapper.json.treeToValue<T>(ContractMapper.yaml.readTree(yaml))

    private fun json(value: Any): String = ContractMapper.json.writeValueAsString(value)

    @Nested
    inner class Types {
        @Test
        fun `a property's type expression is parsed, and an invalid one is rejected`() {
            // expect
            assertThat(Property("list<Shipment>").typeExpression)
                .isEqualTo(TypeExpression.ListOf(TypeExpression.Named("Shipment"), nullableElements = false))

            assertThatThrownBy { Property("list<>").typeExpression }
                .isInstanceOf(IllegalArgumentException::class.java)
                .hasMessage("Invalid type 'list<>'")
        }

        @Test
        fun `each kind reads its own variant and writes its kind`() {
            // given
            val entity = read<TypeDecl>("{kind: entity, identity: [id], properties: {id: {type: uuid}}}")
            val value = read<TypeDecl>("{kind: value, wraps: uuid}")
            val enum = read<TypeDecl>("{kind: enum, members: [a, b]}")
            val union = read<TypeDecl>("{kind: union, discriminator: kind, variants: {a: A}}")
            val projection = read<TypeDecl>("{kind: projection, source: A, properties: [id]}")

            // expect
            assertThat(entity.kind).isEqualTo("entity")
            assertThat(value.kind).isEqualTo("value")
            assertThat(enum.kind).isEqualTo("enum")
            assertThat(union.kind).isEqualTo("union")
            assertThat(projection.kind).isEqualTo("projection")
            assertThat(entity).isInstanceOf(TypeDecl.Entity::class.java)
            assertThat(json(value)).contains("\"kind\":\"value\"")
        }

        @Test
        fun `an unknown kind does not fit the model`() {
            // expect
            assertThatThrownBy { read<TypeDecl>("{kind: other}") }
                .hasStackTraceContaining("Unknown kind 'other'")
        }

        @Test
        fun `a projection is written as a list when every property is named after its source, else as a map`() {
            // given
            val listed = TypeDecl.Projection("Shipment", mapOf("id" to "id", "state" to "state"))
            val yaml =
                "{kind: projection, source: S, properties: {id: id, city: destination.city}, " +
                    "description: Summary}"
            val mapped = read<TypeDecl>(yaml) as TypeDecl.Projection

            // expect
            assertThat(listed.listed).isTrue()
            assertThat(listed.properties).isEqualTo(listOf("id", "state"))
            assertThat(mapped.listed).isFalse()
            assertThat(mapped.properties).isEqualTo(mapOf("id" to "id", "city" to "destination.city"))
            assertThat(mapped.description).isEqualTo("Summary")
        }
    }

    @Nested
    inner class Transitions {
        @Test
        fun `one rule is written as its name`() {
            // given
            val ref = read<TransitionRef>("Shipment.ship")

            // expect
            assertThat(ref.rule).isEqualTo("Shipment.ship")
            assertThat(ref.performed).containsExactly("Shipment.ship")
            assertThat(ref.entity).isEqualTo("Shipment")
            assertThat(ref.json()).isEqualTo("Shipment.ship")
        }

        @Test
        fun `a select names the rule per value, each performed once`() {
            // given
            val yaml = "{select: /action, rules: {ship: Shipment.ship, send: Shipment.ship, hold: Shipment.hold}}"
            val ref = read<TransitionRef>(yaml)

            // expect
            assertThat(ref.rule).isNull()
            assertThat(ref.select).isEqualTo("/action")
            assertThat(ref.performed).containsExactly("Shipment.ship", "Shipment.hold")
            assertThat(ref.entity).isEqualTo("Shipment")
            assertThat(ref.json()).isEqualTo(mapOf("select" to "/action", "rules" to ref.rules))
        }

        @Test
        fun `a select without a pointer or rules performs nothing`() {
            // given
            val ref = read<TransitionRef>("{}")

            // expect
            assertThat(ref.select).isNull()
            assertThat(ref.performed).isEmpty()
            assertThat(ref.entity).isNull()
        }

        @Test
        fun `an operation performs the single rule of its transition`() {
            // expect
            assertThat(read<Operation>("{transition: Shipment.ship}").transition).isEqualTo("Shipment.ship")
            assertThat(read<Operation>("{transition: {select: /a, rules: {}}}").transition).isNull()
            assertThat(read<Operation>("{}").transition).isNull()
        }
    }

    @Nested
    inner class Operations {
        @ParameterizedTest(name = "output {0}")
        @CsvSource(
            nullValues = ["null"],
            value = [
                "Shipment,         Shipment,         false, Shipment",
                "list<Shipment>,   Shipment,         true,  null",
                "list<Shipment?>,  list<Shipment?>,  false, list<Shipment?>",
                "list<string>,     list<string>,     false, list<string>",
                "set<Shipment>,    set<Shipment>,    false, set<Shipment>",
                "null,             null,             false, null",
            ],
        )
        fun `the output names one value, or a bare list of a named type`(
            output: String?,
            type: String?,
            list: Boolean,
            single: String?,
        ) {
            // given
            val operation = Operation(output = output)

            // expect
            assertThat(operation.outputType).isEqualTo(type)
            assertThat(operation.outputList).isEqualTo(list)
            assertThat(operation.singleOutput).isEqualTo(single)
        }

        @Test
        fun `a subject is an entity name, or the entity of the actor`() {
            // given
            val named = read<Subject>("Shipment")
            val actor = read<Subject>("{type: Courier, by: actor}")
            val plain = read<Subject>("{type: Courier}")

            // expect
            assertThat(named.actor).isFalse()
            assertThat(named.json()).isEqualTo("Shipment")
            assertThat(actor.actor).isTrue()
            assertThat(actor.json()).isEqualTo(mapOf("type" to "Courier", "by" to "actor"))
            assertThat(plain).isEqualTo(Subject("Courier"))
        }

        @Test
        fun `a link names the owner and its to-many reference`() {
            // given
            val link = read<LinkOperation>("{to: Shipment.labels, action: add}")

            // expect
            assertThat(link.owner).isEqualTo("Shipment")
            assertThat(link.property).isEqualTo("labels")
            assertThat(link.action).isEqualTo(LinkAction.ADD)
        }

        @Test
        fun `a batch has its wire pointers and the roles within one result`() {
            // given
            val batch = read<Batch>("{atomic: true, wire: {items: '', results: /results}}")

            // expect
            assertThat(batch.maxItems).isNull()
            assertThat(json(batch)).contains("\"items\":\"\"")
            assertThat(Batch.RESULT_ROLES).containsExactly("value", "problem", "index", "status")
        }

        @Test
        fun `a tiebreaker is a field, ascending unless it says otherwise`() {
            // given
            val field = read<Tiebreaker>("id")
            val descending = read<Tiebreaker>("{field: id, direction: desc}")
            val written = read<Tiebreaker>("{field: id}")

            // expect
            assertThat(field.effectiveDirection).isEqualTo(Values.ASC)
            assertThat(field.json()).isEqualTo("id")
            assertThat(descending.effectiveDirection).isEqualTo("desc")
            assertThat(descending.json()).isEqualTo(mapOf("field" to "id", "direction" to "desc"))
            assertThat(written).isEqualTo(Tiebreaker("id"))
        }

        @Test
        fun `a REST reference is one operation or one per source`() {
            // given
            val single = read<RestRef>("api#/paths/~1a/get")
            val perSource = read<RestRef>("{v1: 'old#/paths/~1a/get', v2: 'api#/paths/~1a/get'}")

            // expect
            assertThat(single.all).containsExactly("api#/paths/~1a/get")
            assertThat(perSource.all).containsExactly("old#/paths/~1a/get", "api#/paths/~1a/get")
            assertThat(json(single)).isEqualTo("\"api#/paths/~1a/get\"")
        }
    }

    @Nested
    inner class Shapes {
        @Test
        fun `a command carries a property as one form, or as identity or command`() {
            // expect
            assertThat(read<As>("identity")).isEqualTo(As.IDENTITY)
            assertThat(read<As>("[command, identity]")).isEqualTo(As.IDENTITY_OR_COMMAND)
            assertThat(As.IDENTITY_OR_COMMAND.json()).isEqualTo(listOf("identity", "command"))
            assertThat(As.VALUE.json()).isEqualTo("value")
        }
    }

    @Nested
    inner class Errors {
        private val codes =
            "codes: {late: {category: conflict, type: 'https://x/late'}, gone: {category: conflict}}"

        private fun model(identify: String) =
            read<ErrorModel>(
                """
                {format: problem, validationStatus: 422, identify: $identify, codeTypes: 'https://x/{code}',
                 wire: {fieldErrors: /errors, field: {location: /pointer, code: /code}, locationFormat: pointer},
                 $codes}
                """.trimIndent(),
            )

        @Test
        fun `with identify type a code has its own type, or the template filled in`() {
            // given
            val model = model("type")

            // expect
            assertThat(model.typeOf("late")).isEqualTo("https://x/late")
            assertThat(model.typeOf("gone")).isEqualTo("https://x/gone")
            assertThat(model.wire.code).isNull()
            assertThat(model.wire.field.detail).isNull()
        }

        @Test
        fun `without a template a code without its own type has none`() {
            // given
            val model = model("type").copy(codeTypes = null)

            // expect
            assertThat(model.typeOf("gone")).isNull()
            assertThat(model.typeOf("late")).isEqualTo("https://x/late")
        }

        @Test
        fun `with identify member no code has a type`() {
            // expect
            assertThat(model("member").typeOf("late")).isNull()
        }

        @Test
        fun `the standard codes each have their category`() {
            // expect
            assertThat(StandardCodes.NAMES).contains("required", "notFound", "actorUnresolved").hasSize(33)
            assertThat(StandardCodes.CATEGORIES.getValue("required")).isEqualTo("validation")
            assertThat(StandardCodes.CATEGORIES.getValue("notFound")).isEqualTo("not-found")
            assertThat(StandardCodes.CATEGORIES.getValue("versionMismatch")).isEqualTo("precondition-failed")
        }
    }

    @Nested
    inner class Mappings {
        @Test
        fun `a property comes from one path, or from a path per form`() {
            // given
            val path = read<PropertyMapping>("{from: courier.id, version: stored}")
            val forms = read<PropertyMapping>("{from: {identity: courierId, command: courier}}")
            val custom = read<PropertyMapping>("{custom: true}")

            // expect
            assertThat(path.from!!.paths).containsExactly("courier.id")
            assertThat(path.fromPath).isEqualTo("courier.id")
            assertThat(path.storedVersion).isTrue()
            assertThat(forms.from).isEqualTo(PropertyFrom.Forms("courierId", "courier"))
            assertThat(forms.from!!.paths).containsExactly("courierId", "courier")
            assertThat(forms.fromPath).isNull()
            assertThat(forms.storedVersion).isFalse()
            assertThat(custom.fromPath).isNull()
        }

        @Test
        fun `a converter needs no fields`() {
            // expect
            assertThat(read<Converter>("{}")).isEqualTo(Converter())
        }
    }

    @Nested
    inner class Storage {
        @ParameterizedTest(name = "{0}")
        @CsvSource(
            delimiter = '|',
            value = [
                "none                                       | none",
                "physical                                   | physical",
                "{soft: deleted_at}                         | {\"soft\":\"deleted_at\",\"restore\":false}",
                "{soft: deleted_at, by: by, restore: true}  | {\"soft\":\"deleted_at\",\"by\":\"by\",\"restore\":true}",
            ],
        )
        fun `deletion is none, physical or soft, written as read`(
            yaml: String,
            written: String,
        ) {
            // given
            val mode = read<DeleteMode>(yaml)
            val expected = if (written.startsWith("{")) written else "\"$written\""

            // expect
            assertThat(json(mode)).isEqualTo(expected)
            assertThat(read<DeleteMode>(json(mode))).isEqualTo(mode)
        }

        @Test
        fun `a soft delete restores nothing and records no actor unless it says so`() {
            // expect
            assertThat(DeleteMode.Soft("deleted_at")).isEqualTo(read<DeleteMode>("{soft: deleted_at}"))
        }

        @Test
        fun `a link without a link table is read-only`() {
            // expect
            assertThat(read<Link>("{ownerKey: shipment_id}").readOnly).isTrue()
            assertThat(read<Link>("{table: db#labels, ownerKey: a, targetKey: b}").readOnly).isFalse()
        }

        @Test
        fun `a blob needs only its store and key`() {
            // expect
            assertThat(read<Blob>("{store: photos, key: photo_key}")).isEqualTo(Blob("photos", "photo_key"))
        }
    }

    @Nested
    inner class Sections {
        @Test
        fun `the optional parts of auth, events and navigation may be left out`() {
            // given
            val auth = read<Auth>("{providers: {main: {issuer: 'https://id'}}}")
            val yaml =
                "{type: shipped, source: s, subject: id, data: Shipment, " +
                    "channel: 'events#/channels/a', mode: push}"
            val event = read<Event>(yaml)

            // expect
            assertThat(auth.clients).isEmpty()
            assertThat(auth.providers.getValue("main").verification).isNull()
            assertThat(read<Claims>("{}")).isEqualTo(Claims())
            assertThat(read<ScopeDecl>("{scheme: 'api#/components/securitySchemes/oauth'}").description).isNull()
            assertThat(read<Deprecation>("{since: '2026-01-01'}").sunset).isNull()
            assertThat(event.description).isNull()
            assertThat(read<NavigationEntry>("{view: home}")).isEqualTo(NavigationEntry("home"))
        }

        @Test
        fun `a list view always sends its fixed filters with the actor`() {
            // given
            val yaml = "{operation: list, where: [{field: courier, operator: eq, value: actor}]}"
            val view = read<ListView>(yaml)

            // expect
            assertThat(view.where.single()).isEqualTo(FixedFilter("courier", "eq", "actor"))
            assertThat(view.where.single().field).isEqualTo("courier")
        }

        @Test
        fun `navigation lists both menus by key, primary first`() {
            // given
            val navigation = read<Navigation>("{primary: [{view: a}], secondary: [{view: b}]}")

            // expect
            assertThat(navigation.home).isNull()
            assertThat(navigation.menus.map { it.first }).containsExactly("primary", "secondary")
            assertThat(navigation.menus.map { it.second.single().view }).containsExactly("a", "b")
        }
    }
}
