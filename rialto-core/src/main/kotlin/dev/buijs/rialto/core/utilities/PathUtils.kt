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

import java.nio.file.Path

/** The path of [file] relative to [directory], with `/` as separator, as contracts and diagnostics write paths. */
fun relativePath(
    directory: Path,
    file: Path,
): String =
    directory
        .toAbsolutePath()
        .normalize()
        .relativize(file.toAbsolutePath().normalize())
        .joinToString("/")
