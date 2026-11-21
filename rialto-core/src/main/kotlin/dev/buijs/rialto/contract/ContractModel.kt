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

import com.fasterxml.jackson.annotation.JsonAnyGetter
import com.fasterxml.jackson.annotation.JsonAnySetter
import com.fasterxml.jackson.annotation.JsonProperty

/**
 * A Rialto contract (contract format 1.0.0). The model mirrors the document one to one; the
 * meaning of each section is defined by the profile that owns it.
 *
 * The contract describes one service or application once, independent of language and framework: its data
 * ([types], [shapes]), how that data travels over REST and events ([operations], [events]) and how it is stored
 * ([storage]) and shown ([views]). Validation produces this model; checks, `diff` and generators read it. It holds
 * no logic of its own and no rules: those live in the checks, so the model stays a plain picture of the file.
 *
 * Each property is one top-level section of the document, named as in the file. A section that is absent is
 * empty here, never null, except for the sections that are a single object.
 */
data class Contract(
    val rialto: String,
    val info: Info,
    val sources: Map<String, Source> = emptyMap(),
    val auth: Auth? = null,
    val types: Map<String, TypeDecl> = emptyMap(),
    val shapes: Map<String, Shape> = emptyMap(),
    val errors: ErrorModel? = null,
    val operations: Map<String, Operation> = emptyMap(),
    val events: Map<String, Event> = emptyMap(),
    val streams: Map<String, Stream> = emptyMap(),
    val subscriptions: Map<String, Subscription> = emptyMap(),
    val mappings: List<Mapping> = emptyList(),
    val storage: Map<String, Storage> = emptyMap(),
    val views: Map<String, View> = emptyMap(),
    /** The menu of the application (U5.9). */
    val navigation: Navigation? = null,

    /** Top-level extension keys (`x-<generator>-<name>`, K1.8), kept so a written contract still carries them. */
    @param:JsonAnySetter
    @get:JsonAnyGetter
    val extensions: Map<String, Any?> = emptyMap(),
)

/** The name of what the contract describes, and the version of the contract itself (not of the format). */
data class Info(
    val name: String,
    val version: String,
)

enum class SourceKind {
    @JsonProperty("openapi")
    OPENAPI,

    @JsonProperty("asyncapi")
    ASYNCAPI,

    @JsonProperty("sql")
    SQL,
}

enum class SourceStatus {
    @JsonProperty("current")
    CURRENT,

    @JsonProperty("deprecated")
    DEPRECATED,
}

/**
 * An entry under `sources`: an OpenAPI, AsyncAPI or SQL document the contract points into, so it never repeats
 * what such a document already defines. [path] is relative to the contract, an `https` URL (K4), or for SQL a
 * glob over migrations (K1.9). [Sources] loads it. [version] and [status] apply to API sources, which a contract
 * can list in several versions; [dialect] applies to SQL.
 */
data class Source(
    val kind: SourceKind,
    val path: String,
    val dialect: String? = null,
    val version: String? = null,
    val status: SourceStatus? = null,
    val deprecated: String? = null,
    val sunset: String? = null,
    val successor: String? = null,
)
