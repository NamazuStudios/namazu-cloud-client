// This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
// If a copy of the MPL was not distributed with this file, You can obtain one at
// https://mozilla.org/MPL/2.0/.

package com.namazustudios.cloud.protocol

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ControlMacTest {

    private val key = ControlMac.deriveKey("test-secret")

    @Test
    fun deriveKeyIsDeterministic() {
        assertEquals(
            ControlMac.deriveKey("test-secret").toList(),
            key.toList(),
            "same secret must derive the same key",
        )
        assertNotEquals(
            ControlMac.deriveKey("test-secret").toList(),
            ControlMac.deriveKey("other-secret").toList(),
        )
    }

    @Test
    fun validateAcceptsMacOverIdenticalParts() {
        val mac = ControlMac.compute(key, ControlMac.LABEL_CLIENT_AUTH, "my-client", "nonce-1")
        assertTrue(ControlMac.validate(key, mac, ControlMac.LABEL_CLIENT_AUTH, "my-client", "nonce-1"))
    }

    @Test
    fun validateRejectsWrongKey() {
        val mac = ControlMac.compute(key, ControlMac.LABEL_CLIENT_AUTH, "my-client", "nonce-1")
        assertFalse(
            ControlMac.validate(
                ControlMac.deriveKey("wrong-secret"),
                mac,
                ControlMac.LABEL_CLIENT_AUTH,
                "my-client",
                "nonce-1",
            ),
        )
    }

    @Test
    fun validateRejectsTamperedParts() {
        val mac = ControlMac.compute(key, ControlMac.LABEL_SERVER_AUTH, "c", "n", "nc")
        assertFalse(ControlMac.validate(key, mac, ControlMac.LABEL_SERVER_AUTH, "c", "n", "nX"))
        assertFalse(ControlMac.validate(key, mac, ControlMac.LABEL_SERVER_AUTH, "c", "n"))
        assertFalse(ControlMac.validate(key, mac, ControlMac.LABEL_CLIENT_AUTH, "c", "n", "nc"))
    }

    @Test
    fun signaturesAreDistinctPerLabel() {
        val clientAuth = ControlMac.compute(key, ControlMac.LABEL_CLIENT_AUTH, "c", "n")
        val serverAuth = ControlMac.compute(key, ControlMac.LABEL_SERVER_AUTH, "c", "n")
        assertNotEquals(clientAuth, serverAuth, "labels must not collide")
    }

    @Test
    fun nonceIsUniqueAndHex() {
        val a = ControlMac.newNonce()
        val b = ControlMac.newNonce()
        assertNotEquals(a, b)
        assertEquals(64, a.length) // 32 bytes, 2 hex chars per byte
        assertTrue(a.all { it in "0123456789abcdef" })
    }

    @Test
    fun validateRejectsMalformedHex() {
        val mac = ControlMac.compute(key, ControlMac.LABEL_CLIENT_AUTH, "c", "n")
        assertFalse(ControlMac.validate(key, mac.take(20), ControlMac.LABEL_CLIENT_AUTH, "c", "n"))
        assertFalse(ControlMac.validate(key, "zzzz", ControlMac.LABEL_CLIENT_AUTH, "c", "n"))
    }
}