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

/**
 * The `errors` section (errors profile, E): how errors look on the wire, how a client tells one from another
 * ([identify]), and the application's own error [codes] next to the [StandardCodes].
 */
data class ErrorModel(
    val format: String,
    val validationStatus: Int,
    val types: Map<String, String> = emptyMap(),
    val identify: String,
    /** With `identify: type`: the URI template, with one `{code}`, of every code without its own `type` (E2.2). */
    val codeTypes: String? = null,
    val wire: ErrorWire,
    val codes: Map<String, ErrorCode> = emptyMap(),
) {
    /**
     * The problem `type` of [code] with `identify: type`: its own `type`, else [codeTypes] with
     * the code filled in; `null` with `identify: member`.
     */
    fun typeOf(code: String): String? =
        if (identify != "type") {
            null
        } else {
            codes[code]?.type ?: codeTypes?.replace(CODE_PLACEHOLDER, code)
        }

    companion object {
        /** The placeholder of [codeTypes]. */
        const val CODE_PLACEHOLDER: String = "{code}"
    }
}

/** The standard codes of the errors profile (Standard codes), which application codes must not reuse (E1.1). */
object StandardCodes {
    /** Each standard code with its category. */
    val CATEGORIES: Map<String, String> =
        listOf(
            "required",
            "type",
            "enum",
            "const",
            "oneOf",
            "minLength",
            "maxLength",
            "pattern",
            "format",
            "minimum",
            "maximum",
            "exclusiveMinimum",
            "exclusiveMaximum",
            "multipleOf",
            "minItems",
            "maxItems",
            "uniqueItems",
            "additionalProperties",
            "malformed",
            "precision",
            "invalidQuery",
            "invalidCursor",
            "idempotencyKeyReused",
        ).associateWith { "validation" } +
            mapOf(
                "notFound" to "not-found",
                "unique" to "conflict",
                "reference" to "conflict",
                "invalidTransition" to "conflict",
                "idempotencyKeyInUse" to "conflict",
                "versionMismatch" to "precondition-failed",
                "preconditionRequired" to "precondition-required",
                "maxSize" to "payload-too-large",
                "mediaType" to "unsupported-media-type",
                "actorUnresolved" to "authorization",
            )

    /** The standard code names. */
    val NAMES: Set<String> = CATEGORIES.keys
}

data class ErrorWire(
    val code: String? = null,
    val fieldErrors: String,
    val field: FieldErrorWire,
    val locationFormat: String,
)

data class FieldErrorWire(
    val location: String,
    val code: String,
    val detail: String? = null,
)

data class ErrorCode(
    val category: String,
    val title: String? = null,
    val type: String? = null,
    val status: Int? = null,
    val description: String? = null,
)
