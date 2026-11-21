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

import com.fasterxml.jackson.annotation.JsonProperty

/**
 * The `auth` section (auth profile, S): who issues tokens ([providers]), which clients sign in ([clients]), how
 * claims identify the caller ([claims]), and the [scopes] and [apiKeys] operations require.
 */
data class Auth(
    val providers: Map<String, Provider>,
    val clients: Map<String, Client> = emptyMap(),
    val claims: Claims? = null,
    val scopes: Map<String, ScopeDecl> = emptyMap(),
    val apiKeys: Map<String, ApiKey> = emptyMap(),
)

data class Provider(
    val issuer: String,
    /** Absent means jwt. */
    val verification: Verification? = null,
)

/** How a server verifies access tokens of a provider (S3.1, S6.4). */
enum class Verification {
    @JsonProperty("jwt")
    JWT,

    @JsonProperty("introspection")
    INTROSPECTION,
}

/** An API key of services: its OpenAPI apiKey scheme and the authored lookup (S6). */
data class ApiKey(
    val scheme: String,
    val lookup: String,
)

enum class Flow {
    @JsonProperty("authorizationCode")
    AUTHORIZATION_CODE,

    @JsonProperty("clientCredentials")
    CLIENT_CREDENTIALS,
}

data class Client(
    val provider: String,
    val clientId: String,
    val flow: Flow,
    val scopes: List<String> = emptyList(),
)

data class Claims(
    val subject: String? = null,
    val roles: List<String> = emptyList(),
    val tenant: String? = null,
    val service: String? = null,
)

data class ScopeDecl(
    val description: String? = null,
    val scheme: String,
)
