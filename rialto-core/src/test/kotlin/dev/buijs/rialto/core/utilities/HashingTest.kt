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

/** The SHA-256 that pins remote sources in the lock of a contract and keys the cache. */
class HashingTest {
    @Test
    fun `the hash is the lower-case hex SHA-256`() {
        // expect
        assertThat(sha256("abc".toByteArray()))
            .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad")
    }

    @Test
    fun `no bytes have the hash of the empty input`() {
        // expect
        assertThat(sha256(ByteArray(0)))
            .isEqualTo("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855")
    }

    @Test
    fun `every byte is two hex digits, also below 0x10 and above 0x7f`() {
        // when
        val hash = sha256(byteArrayOf(0x00, 0x0f, 0x7f, 0x80.toByte(), 0xff.toByte()))

        // then
        assertThat(hash).hasSize(64).matches("[0-9a-f]{64}")
    }

    @Test
    fun `the same bytes give the same hash, other bytes another`() {
        // expect
        assertThat(sha256("{}".toByteArray())).isEqualTo(sha256("{}".toByteArray()))
        assertThat(sha256("{}".toByteArray())).isNotEqualTo(sha256("[]".toByteArray()))
    }
}
