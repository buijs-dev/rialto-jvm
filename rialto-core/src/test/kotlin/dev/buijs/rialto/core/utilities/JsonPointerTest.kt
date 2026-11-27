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
package dev.buijs.rialto.core.utilities

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** JSON Pointers (RFC 6901) as diagnostics write them. */
class JsonPointerTest {
    @Test
    fun `a segment escapes tilde and slash`() {
        // expect
        assertThat(JsonPointer.segment("a/b~c")).isEqualTo("a~1b~0c")
    }

    @Test
    fun `a segment escapes the tilde first, so an escape in a name stays apart from a slash`() {
        // expect
        assertThat(JsonPointer.segment("~1")).isEqualTo("~01")
        assertThat(JsonPointer.segment("/")).isEqualTo("~1")
    }

    @Test
    fun `a segment without tilde or slash stays as it is, also when empty`() {
        // expect
        assertThat(JsonPointer.segment("types.Thing")).isEqualTo("types.Thing")
        assertThat(JsonPointer.segment("")).isEmpty()
    }

    @Test
    fun `a pointer joins its segments, each escaped`() {
        // expect
        assertThat(JsonPointer.of("types", "a/b", "properties")).isEqualTo("/types/a~1b/properties")
    }

    @Test
    fun `a pointer takes any segment as text, such as a list index`() {
        // expect
        assertThat(JsonPointer.of("mappings", 0, "source")).isEqualTo("/mappings/0/source")
    }

    @Test
    fun `a pointer from a list is the same as from its segments`() {
        // expect
        assertThat(JsonPointer.of(listOf("types", "a~b"))).isEqualTo(JsonPointer.of("types", "a~b"))
    }

    @Test
    fun `a pointer without segments is the whole document`() {
        // expect
        assertThat(JsonPointer.of()).isEmpty()
        assertThat(JsonPointer.of(emptyList())).isEmpty()
    }

    @Test
    fun `an empty segment is kept`() {
        // expect
        assertThat(JsonPointer.of("a", "")).isEqualTo("/a/")
    }

    @Test
    fun `a pointer is taken apart into its unescaped segments`() {
        // expect
        assertThat(JsonPointer.segments("/paths/~1a~0b/get")).containsExactly("paths", "/a~b", "get")
        assertThat(JsonPointer.segments("/a/")).containsExactly("a", "")
        assertThat(JsonPointer.segments("")).isEmpty()
        assertThat(JsonPointer.segments("a/b")).isNull()
    }

    @Test
    fun `a pointer taken apart and joined again is the same`() {
        // expect
        assertThat(JsonPointer.of(JsonPointer.segments("/a~1b/~0c")!!)).isEqualTo("/a~1b/~0c")
    }
}
