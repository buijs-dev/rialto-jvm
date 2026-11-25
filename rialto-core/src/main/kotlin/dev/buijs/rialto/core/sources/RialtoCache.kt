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

import dev.buijs.rialto.core.utilities.sha256
import dev.buijs.rialto.core.utilities.writeAtomically
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readBytes

/**
 * The cache of Rialto tooling: `~/.cache/rialto`, or the system property `rialto.cache`, else the environment
 * variable `RIALTO_CACHE`.
 *
 * It holds two things: the remote sources by content (`sha256/<hash>/<file name>`, K4), so a pinned version can
 * be read again without network; and the file locks under `locks`, through which runs that work on the same
 * contract take turns changing `rialto.lock` ([RialtoLock]).
 */
object RialtoCache {
    /** The root of the cache. */
    fun dir(): Path =
        dir(System.getProperty("rialto.cache"), System.getenv("RIALTO_CACHE"), System.getProperty("user.home"))

    /** The root of the cache: the system [property], else the [environment] variable, else under [home]. */
    internal fun dir(
        property: String?,
        environment: String?,
        home: String,
    ): Path = Path.of(property ?: environment ?: Path.of(home, ".cache", "rialto").toString())

    /** The directory of the file locks. */
    internal fun locks(): Path = dir().resolve("locks")

    /** The cached document [name] with [hash], or null when it is missing or its content no longer has [hash]. */
    internal fun cached(
        hash: String,
        name: String,
    ): Path? = document(hash, name).takeIf { it.exists() && sha256(it.readBytes()) == hash }

    /** Stores [body] as the document [name] with [hash], atomically, and returns its file. */
    internal fun store(
        hash: String,
        name: String,
        body: ByteArray,
    ): Path =
        document(hash, name).apply {
            parent.createDirectories()
            writeAtomically(body)
        }

    /** Where the document [name] with [hash] lives in the cache: `sha256/<hash>/<name>`. */
    private fun document(
        hash: String,
        name: String,
    ): Path = dir().resolve("sha256").resolve(hash).resolve(name)
}
