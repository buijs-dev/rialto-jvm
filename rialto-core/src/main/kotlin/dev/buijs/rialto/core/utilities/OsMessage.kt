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

import java.io.IOException
import java.nio.file.AccessDeniedException
import java.nio.file.FileSystemException
import java.nio.file.NoSuchFileException

/** The operating system's message for a failed file operation, without the path of the file. */
internal fun osMessage(e: IOException): String =
    when (e) {
        is AccessDeniedException -> "Permission denied"
        is NoSuchFileException -> "No such file or directory"
        is FileSystemException -> e.reason ?: e.javaClass.simpleName
        else -> e.message ?: e.javaClass.simpleName
    }
