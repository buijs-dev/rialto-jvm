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

/**
 * The values a contract document can hold, as the JSON Schema of the specification names them in an `enum` or
 * `const`: one constant per spelling.
 *
 * Where [Keys] are the names of the document, these are the words written after them: the `kind` of a type, the
 * `role` of a shape property, the members of each enum of the model. The model spells them out to bind an enum
 * constant to its word, or to tell a variant written as a word from one written as a mapping. A value that is
 * misspelled there is not a compile error: the contract fails to read, or reads as the wrong variant. Keeping them
 * in one place, and testing each against the schema, turns that into a failing test.
 *
 * A value has one constant however often the format uses the word: [ACTOR] is a role, the `by` of a subject, a
 * value a transition sets and the source of a scope.
 */
internal object Values {
    // The kind of a type ------------------------------------------------------------------------
    const val ENTITY: String = "entity"
    const val ENUM: String = "enum"
    const val PROJECTION: String = "projection"
    const val UNION: String = "union"
    const val VALUE: String = "value"

    // Other values ------------------------------------------------------------------------------
    const val ACTOR: String = "actor"
    const val ADD: String = "add"
    const val APPLICATION: String = "application"
    const val ASC: String = "asc"
    const val ASYNCAPI: String = "asyncapi"
    const val AUTHORIZATION_CODE: String = "authorizationCode"
    const val BUSINESS: String = "business"
    const val CALLER: String = "caller"
    const val CLIENT_CREDENTIALS: String = "clientCredentials"
    const val COMMAND: String = "command"
    const val CREATE: String = "create"
    const val CURRENT: String = "current"
    const val DECODE: String = "decode"
    const val DEPRECATED: String = "deprecated"
    const val DERIVED: String = "derived"
    const val IDENTITY: String = "identity"
    const val INCLUDE: String = "include"
    const val INTROSPECTION: String = "introspection"
    const val JWT: String = "jwt"
    const val MERGE_PATCH: String = "merge-patch"
    const val NONE: String = "none"
    const val NOW: String = "now"
    const val ONLY: String = "only"
    const val OPENAPI: String = "openapi"
    const val OWNED: String = "owned"
    const val PHYSICAL: String = "physical"
    const val REFERENCE: String = "reference"
    const val REMOVE: String = "remove"
    const val REPLACE: String = "replace"
    const val SQL: String = "sql"
    const val STORAGE: String = "storage"
    const val TENANT: String = "tenant"
    const val UPDATE: String = "update"
}
