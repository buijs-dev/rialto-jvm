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

import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.CopyOption
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import kotlin.io.path.createTempFile
import kotlin.io.path.deleteIfExists
import kotlin.io.path.name
import kotlin.io.path.writeBytes

/**
 * Writes [bytes] to this file atomically: to a temporary file next to it, then renamed over it, so a reader sees
 * either the old content or the new, never a part. Where the file system has no atomic rename, a plain one is used.
 */
internal fun Path.writeAtomically(bytes: ByteArray) {
    val target = toAbsolutePath()
    val temporary = createTempFile(directory = target.parent, prefix = ".${target.name}", suffix = ".tmp")
    try {
        temporary.writeBytes(bytes)
        try {
            moveFile(temporary, target, arrayOf(ATOMIC_MOVE, REPLACE_EXISTING))
        } catch (_: AtomicMoveNotSupportedException) {
            moveFile(temporary, target, arrayOf(REPLACE_EXISTING))
        }
    } finally {
        temporary.deleteIfExists()
    }
}

/** Moves a file; replaceable in tests, as no file system of the JDK lacks an atomic move. */
internal var moveFile: (Path, Path, Array<out CopyOption>) -> Unit = { from, to, options ->
    Files.move(from, to, *options)
}
