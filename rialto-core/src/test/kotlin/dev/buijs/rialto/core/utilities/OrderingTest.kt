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

/** File names in code point order (K1.9, K5.2), not in the UTF-16 order of [String.compareTo]. */
class OrderingTest {
    private val emoji = "😀" // U+1F600, outside the Basic Multilingual Plane
    private val fullwidth = "！" // U+FF01

    @Test
    fun `a character outside the basic plane comes after one below it, unlike in UTF-16 order`() {
        // expect
        assertThat(emoji < fullwidth).isTrue()
        assertThat(listOf(emoji, fullwidth).sortedWith(codePointOrder)).containsExactly(fullwidth, emoji)
        assertThat(listOf("b.yaml", "a.yaml", "a$emoji.yaml").sortedWith(codePointOrder))
            .containsExactly("a.yaml", "a$emoji.yaml", "b.yaml")
    }

    @Test
    fun `equal strings are equal, and a prefix comes first`() {
        // expect
        assertThat(codePointOrder.compare("a$emoji", "a$emoji")).isZero()
        assertThat(codePointOrder.compare("a", "a$emoji")).isNegative()
        assertThat(codePointOrder.compare("a$emoji", "a")).isPositive()
        assertThat(codePointOrder.compare("", "")).isZero()
    }
}
