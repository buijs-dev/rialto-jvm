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

package dev.buijs.rialto.core.sources

import dev.buijs.rialto.core.utilities.JsonPointer

/**
 * The OpenAPI operation a REST binding of an operation names (`rest`): `<source>#/paths/<path>/<method>`, taken
 * apart. Only such a pointer names an operation; one that lands elsewhere in the document, such as the path item
 * `/paths/~1orders` or its `parameters`, does not.
 *
 * @property source the API source, by its name under `sources`
 * @property path the path template, such as `/orders/{orderId}`
 * @property method the HTTP method in lower case, such as `get`
 */
data class RestOperation(
    val source: String,
    val path: String,
    val method: String,
) {
    companion object {
        /** The HTTP methods of an OpenAPI path item. */
        val METHODS: Set<String> = setOf("get", "put", "post", "delete", "options", "head", "patch", "trace")

        /** The operation [reference] names, or null when it does not point at `/paths/<path>/<method>`. */
        fun of(reference: String): RestOperation? {
            val ref = ExternalRef.of(reference)
            val segments = JsonPointer.segments(ref.location) ?: return null
            return segments
                .takeIf { it.size == 3 && it[0] == "paths" && it[2] in METHODS }
                ?.let { (_, path, method) -> RestOperation(ref.source, path, method) }
        }
    }
}
