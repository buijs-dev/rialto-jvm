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

import java.security.MessageDigest

private val SHA256 = Regex("[0-9a-f]{64}")

/** The SHA-256 of [bytes] in lower-case hex: the pin in the lock of a contract and the key of the cache. */
fun sha256(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

/** Whether [text] is an SHA-256 as [sha256] writes it: 64 lower-case hex digits. */
fun isSha256(text: String): Boolean = SHA256.matches(text)
