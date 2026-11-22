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

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.nio.file.Path

/** Paths relative to a directory, as contracts and diagnostics write them. */
class PathUtilsTest {
    @Test
    fun `a file below the directory is its path from there, with slashes`() {
        // expect
        assertThat(relativePath(Path.of("/a/b"), Path.of("/a/b/c/d.yaml"))).isEqualTo("c/d.yaml")
    }

    @Test
    fun `a file outside the directory climbs out of it`() {
        // expect
        assertThat(relativePath(Path.of("/a/b/build/rialto"), Path.of("/a/b/c.yaml"))).isEqualTo("../../c.yaml")
    }

    @Test
    fun `the directory itself is the empty path`() {
        // expect
        assertThat(relativePath(Path.of("/a/b"), Path.of("/a/b"))).isEmpty()
    }

    @Test
    fun `both paths are normalized first`() {
        // expect
        assertThat(relativePath(Path.of("/a/./b/../b"), Path.of("/a/b/c/../d.yaml"))).isEqualTo("d.yaml")
    }

    @Test
    fun `relative paths are read from the working directory`() {
        // expect
        assertThat(relativePath(Path.of("x"), Path.of("x/y/z.yaml"))).isEqualTo("y/z.yaml")
        assertThat(relativePath(Path.of("x"), Path.of("x/y/z.yaml").toAbsolutePath())).isEqualTo("y/z.yaml")
    }
}
