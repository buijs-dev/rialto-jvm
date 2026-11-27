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

/**
 * Strings in code point order, as the specification orders file names (K1.9, K5.2). [String.compareTo] compares
 * UTF-16 units instead, which puts a character outside the Basic Multilingual Plane, such as an emoji, before one
 * from U+E000 to U+FFFF; in code point order it comes after.
 */
val codePointOrder: Comparator<String> =
    Comparator { a, b ->
        var i = 0
        var j = 0
        var order = 0
        while (order == 0 && i < a.length && j < b.length) {
            val x = a.codePointAt(i)
            val y = b.codePointAt(j)
            order = x.compareTo(y)
            i += Character.charCount(x)
            j += Character.charCount(y)
        }

        if (order != 0) order else (a.length - i).compareTo(b.length - j)
    }
