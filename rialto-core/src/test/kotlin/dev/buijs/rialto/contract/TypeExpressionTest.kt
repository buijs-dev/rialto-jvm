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

import dev.buijs.rialto.contract.TypeExpression.ListOf
import dev.buijs.rialto.contract.TypeExpression.MapOf
import dev.buijs.rialto.contract.TypeExpression.Named
import dev.buijs.rialto.contract.TypeExpression.Scalar
import dev.buijs.rialto.contract.TypeExpression.SetOf
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class TypeExpressionTest {
    @Test
    fun `parses scalars, decimals and named types`() {
        assertThat(TypeExpression.parse("zonedDateTime")).isEqualTo(Scalar("zonedDateTime"))
        assertThat(TypeExpression.parse("decimal(10,2)")).isEqualTo(Scalar("decimal", 10, 2))
        assertThat(TypeExpression.parse("Shipment")).isEqualTo(Named("Shipment"))
    }

    @Test
    fun `parses collections with nullable elements`() {
        assertThat(
            TypeExpression.parse("list<string?>"),
        ).isEqualTo(ListOf(Scalar("string"), nullableElements = true))
        assertThat(
            TypeExpression.parse("map<uuid, Shipment>"),
        ).isEqualTo(MapOf(Scalar("uuid"), Named("Shipment"), nullableValues = false))
    }

    @Test
    fun `rejects invalid expressions`() {
        listOf(
            "decimal(2,3)",
            // More digits than an integer holds, which the JSON Schema allows.
            "decimal(99999999999,2)",
            "decimal(5,99999999999)",
            "map<boolean, string>",
            "string?",
            "list<>",
            "shipment",
            "set<decimal(2,3)>",
            "map<string>",
            "map<nope, string>",
            "map<string, nope>",
            "list<nope?>",
        ).forEach {
            assertThat(TypeExpression.parse(it)).describedAs(it).isNull()
        }
    }

    @Test
    fun `parses sets and maps with nullable values, and names the types an expression refers to`() {
        val map = TypeExpression.parse(" map<Status, Shipment?> ")

        assertThat(
            TypeExpression.parse("set<Tag>"),
        ).isEqualTo(SetOf(Named("Tag"), nullableElements = false))
        assertThat(map).isEqualTo(MapOf(Named("Status"), Named("Shipment"), nullableValues = true))
        assertThat(map!!.names()).containsExactly("Status", "Shipment")
        assertThat(TypeExpression.parse("list<Line>")!!.names()).containsExactly("Line")
        assertThat(TypeExpression.parse("set<Tag>")!!.names()).containsExactly("Tag")
        assertThat(Scalar("string").names()).isEmpty()
    }

    @Test
    fun `only a decimal carries a precision and scale`() {
        assertThat(Scalar("string")).matches { it.precision == null && it.scale == null }
    }
}
