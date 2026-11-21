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

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isRegularFile

/**
 * The path patterns of a contract: those of `include`, and of an SQL source over migrations. `*` stands for any
 * text within one path segment, `**` for any number of segments.
 */
internal object Glob {
    /**
     * A path pattern of `include`: `*` within one segment, `**` any number of segments, also none. A final `**`
     * matches every file below its directory.
     */
    fun regex(pattern: String): Regex {
        val segments = pattern.split('/')
        val regex =
            segments.withIndex().joinToString("") { (index, segment) ->
                val last = index == segments.lastIndex
                when {
                    segment == "**" && last -> {
                        "(?:[^/]+/)*[^/]+"
                    }
                    segment == "**" -> {
                        "(?:[^/]+/)*"
                    }
                    else -> {
                        segment.split('*').joinToString("[^/]*") { Regex.escape(it) } +
                            if (last) "" else "/"
                    }
                }
            }

        return Regex(regex)
    }

    /**
     * The files [pattern] (see [regex]) matches, relative to [base]: for `include` and for an SQL source over
     * migrations. The walk starts at the directory before the first segment with `*`, so it only reads the
     * directories the pattern can match.
     */
    fun files(
        base: Path,
        pattern: String,
    ): List<Path> {
        val fixed = pattern.split('/').dropLast(1).takeWhile { '*' !in it }

        val directory = base.resolve(fixed.joinToString("/")).toAbsolutePath().normalize()
        if (!Files.isDirectory(directory)) {
            return emptyList()
        }

        val regex = regex(pattern)
        return Files.walk(directory).use { files ->
            files.filter { it.isRegularFile() && regex.matches(relativePath(base, it)) }.toList()
        }
    }
}
