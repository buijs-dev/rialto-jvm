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

import dev.buijs.rialto.core.model.Keys
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** Building pointers and collecting references. */
class ReferencesTest {
    @Test
    fun `each segment of a pointer is escaped as it is added`() {
        assertThat((Pointer.of(Keys.AUTH, "a/b") / "c~d" / 0).text).isEqualTo("/auth/a~1b/c~0d/0")
    }

    @Test
    fun `a name or reference that is not set is skipped`() {
        // when
        val references =
            collectReferences {
                type(name = null, at = Pointer("/a"))
                external(reference = null, at = Pointer("/b"))
                column(table = "db#t", column = null, at = Pointer("/c"))
            }

        // then
        assertThat(references).isEmpty()
    }

    @Test
    fun `a column is located in its table, a list by index, and a name with # in a source`() {
        // when
        val references =
            collectReferences {
                column(table = "db#t", column = "c", at = Pointer("/c"))
                each(kind = Kind.EVENT, names = listOf("A", "B"), at = Pointer("/emits"))
                dataOrExternal(reference = "api#/components/schemas/X", at = Pointer("/source"))
                dataOrExternal(reference = "Shape", at = Pointer("/target"))
            }

        // then
        assertThat(references).containsExactly(
            externalAt("db#t.c", "/c"),
            nameAt(Kind.EVENT, "A", "/emits/0"),
            nameAt(Kind.EVENT, "B", "/emits/1"),
            externalAt("api#/components/schemas/X", "/source"),
            nameAt(Kind.DATA, "Shape", "/target"),
        )
    }
}
