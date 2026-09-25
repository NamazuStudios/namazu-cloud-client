// This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
// If a copy of the MPL was not distributed with this file, You can obtain one at
// https://mozilla.org/MPL/2.0/.

package com.namazustudios.cloud.protocol

import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.text.Charsets.UTF_8

/**
 * The mutual-authentication primitives for the cloud-control channel.
 *
 * A single shared secret `S` is provisioned into each managed instance. Both sides derive the same
 * HMAC key `K = SHA256(S)`; `K` is what the server is required to hold at rest (encrypted, never
 * hashed — an HMAC cannot be verified from a hash of the key). The connector and the control plane
 * each prove knowledge of `K` with one MAC over a per-connection nonce.
 */
object ControlMac {

    /** Canonical label prefix for the client->server handshake MAC. */
    const val LABEL_CLIENT_AUTH = "client-auth.v1"

    /** Canonical label prefix for the server->client handshake MAC. */
    const val LABEL_SERVER_AUTH = "server-auth.v1"

    private const val HEX = "0123456789abcdef"

    private val RANDOM = SecureRandom()

    /** Derives the HMAC key from the shared secret: `K = SHA256(S)`. */
    fun deriveKey(secret: String): ByteArray =
        sha256(secret.toByteArray(UTF_8))

    /**
     * Computes the canonical hex MAC over `label` and [parts]:
     *
     * ```
     * MAC = hex(HMAC_SHA256(K, label.part1.part2.…))
     * ```
     *
     * @throws IllegalArgumentException if [key] is empty.
     */
    fun compute(key: ByteArray, label: String, vararg parts: String): String {
        require(key.isNotEmpty()) { "empty HMAC key" }
        val input = input(label, parts)
        val mac = Mac.getInstance("HmacSHA256").apply {
            init(SecretKeySpec(key, "HmacSHA256"))
        }
        return mac.doFinal(input.toByteArray(UTF_8)).toHex()
    }

    /**
     * Constant-time verification of a computed [macHex] against [expectedMacHex]. Length
     * difference is not masked (both are hex of SHA-256), so a plain `isEqual` is adequate.
     */
    fun validate(key: ByteArray, macHex: String, label: String, vararg parts: String): Boolean {
        if (key.isEmpty()) return false
        val mac = Mac.getInstance("HmacSHA256").apply {
            init(SecretKeySpec(key, "HmacSHA256"))
        }
        val actual = mac.doFinal(input(label, parts).toByteArray(UTF_8))
        return MessageDigest.isEqual(actual, hexToBytes(macHex))
    }

    /** Generates a fresh 32-byte challenge/response nonce as lowercase hex. */
    fun newNonce(): String {
        val bytes = ByteArray(32)
        RANDOM.nextBytes(bytes)
        return bytes.toHex()
    }

    private fun input(label: String, parts: Array<out String>): String =
        parts.fold(label) { acc, part -> "$acc.$part" }

    private fun sha256(bytes: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(bytes)

    internal fun ByteArray.toHex(): String {
        val out = StringBuilder(size * 2)
        for (b in this) {
            out.append(HEX[(b.toInt() ushr 4) and 0x0f])
            out.append(HEX[b.toInt() and 0x0f])
        }
        return out.toString()
    }

    internal fun hexToBytes(hex: String): ByteArray {
        if (hex.length % 2 != 0) return ByteArray(0)
        val out = ByteArray(hex.length / 2)
        for (i in out.indices) {
            val hi = Character.digit(hex[i * 2], 16)
            val lo = Character.digit(hex[i * 2 + 1], 16)
            if (hi < 0 || lo < 0) return ByteArray(0)
            out[i] = ((hi shl 4) or lo).toByte()
        }
        return out
    }
}