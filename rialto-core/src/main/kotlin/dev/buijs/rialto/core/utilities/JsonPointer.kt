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

/** JSON Pointers (RFC 6901), as diagnostics use them to point at an element of a document. */
object JsonPointer {
    /** Escapes one JSON Pointer segment. */
    fun segment(name: String): String = name.replace("~", "~0").replace("/", "~1")

    /** A JSON Pointer from its [segments], each escaped. */
    fun of(vararg segments: Any): String = of(segments.map { it.toString() })

    /** A JSON Pointer from its [segments], each escaped. */
    fun of(segments: Iterable<String>): String = segments.joinToString("") { "/" + segment(it) }

    /** The unescaped segments of [pointer]; none for the whole document, null when it is not a JSON Pointer. */
    fun segments(pointer: String): List<String>? =
        when {
            pointer.isEmpty() -> emptyList()
            !pointer.startsWith('/') -> null
            else -> pointer.drop(1).split('/').map { it.replace("~1", "/").replace("~0", "~") }
        }
}
