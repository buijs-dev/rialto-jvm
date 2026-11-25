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

import dev.buijs.rialto.core.model.Contract
import dev.buijs.rialto.core.model.Keys

/**
 * The references of the `auth` section: the provider of each client, and the security scheme in a source of each
 * scope and API key.
 */
internal fun Contract.authReferences(): List<Reference> =
    collectReferences {
        val auth = auth ?: return@collectReferences
        val at = Pointer.of(Keys.AUTH)
        auth.clients.forEach { (name, client) ->
            name(Kind.PROVIDER, client.provider, at / Keys.CLIENTS / name / Keys.PROVIDER)
        }

        auth.scopes.forEach { (scope, declaration) ->
            external(declaration.scheme, at / Keys.SCOPES / scope / Keys.SCHEME)
        }

        auth.apiKeys.forEach { (key, declaration) ->
            external(declaration.scheme, at / Keys.API_KEYS / key / Keys.SCHEME)
        }
    }

/** The references of the `sources` section: the successor of a source is a declared source. */
internal fun Contract.sourceReferences(): List<Reference> =
    collectReferences {
        sources.forEach { (name, source) ->
            name(Kind.SOURCE, source.successor, Pointer.of(Keys.SOURCES, name, Keys.SUCCESSOR))
        }
    }
