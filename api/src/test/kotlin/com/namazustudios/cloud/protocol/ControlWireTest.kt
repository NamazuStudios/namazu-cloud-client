// This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
// If a copy of the MPL was not distributed with this file, You can obtain one at
// https://mozilla.org/MPL/2.0/.

package com.namazustudios.cloud.protocol

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ControlWireTest {

    private fun roundTrip(frame: ControlFrame): ControlFrame {
        val json = ControlWire.encode(frame)
        return ControlWire.decode(json)
    }

    @Test
    fun challengeRoundTrips() {
        val frame = ControlChallenge(nonce = "abc123", expiresAt = 1_700_000_000_123L)
        assertEquals(frame, roundTrip(frame))
    }

    @Test
    fun responseRoundTrips() {
        val frame = ControlResponse("my-client", "srv-nonce", "cli-nonce", "deadbeef")
        assertEquals(frame, roundTrip(frame))
    }

    @Test
    fun serverAuthRoundTrips() {
        val frame = ControlServerAuth("cafebabe")
        assertEquals(frame, roundTrip(frame))
    }

    @Test
    fun byeRoundTrips() {
        assertEquals(ControlBye(reason = null), roundTrip(ControlBye()))
        assertEquals(ControlBye(reason = "going away"), roundTrip(ControlBye(reason = "going away")))
    }

    @Test
    fun requestSessionRoundTrips() {
        val frame = ControlRequestSession(
            requestId = "req-1",
            actor = ControlActor("u-1", "owner@example.com", "SUPERUSER"),
            expiresAt = null,
        )
        assertEquals(frame, roundTrip(frame))

        val withExpiry = ControlRequestSession(
            requestId = "req-2",
            actor = ControlActor("u-2", "dev@example.com", "SUPERUSER"),
            expiresAt = 1_700_000_000_999L,
        )
        assertEquals(withExpiry, roundTrip(withExpiry))
    }

    @Test
    fun sessionRoundTrips() {
        val frame = ControlSession(
            requestId = "req-3",
            sessionSecret = "ses_secret_123",
            expiresAt = 1_700_000_000_999L,
            userId = "u-9",
            userName = "cloud-user-u-9",
        )
        assertEquals(frame, roundTrip(frame))
    }

    @Test
    fun errorRoundTrips() {
        val frame = ControlError(code = ControlErrors.UNAUTHORIZED, message = "mac mismatch", requestId = "req-9")
        assertEquals(frame, roundTrip(frame))
        assertNull(roundTrip(ControlError(code = ControlErrors.CLOSED)).let { it as ControlError }.requestId)
    }

    @Test
    fun ackRoundTrips() {
        assertEquals(ControlAck("req-7", ok = true), roundTrip(ControlAck("req-7", ok = true)))
        assertEquals(ControlAck("req-8", ok = false), roundTrip(ControlAck("req-8", ok = false)))
    }

    @Test
    fun decodeHandWrittenChallenge() {
        val frame = ControlWire.decode("""{"type":"challenge","nonce":"n1","expiresAt":1700000000000}""")
        assertEquals(ControlChallenge("n1", 1_700_000_000_000L), frame)
    }

    @Test
    fun decodeAcceptsSurroundingWhitespace() {
        val frame = ControlWire.decode("  {\"type\":\"ack\",\"requestId\":\"r\",\"ok\":true}  ")
        assertEquals(ControlAck("r", ok = true), frame)
    }

    @Test
    fun fieldOrderIsIrrelevant() {
        val frame = ControlWire.decode("""{"expiresAt":1700000000000,"nonce":"n1","type":"challenge"}""")
        assertEquals(ControlChallenge("n1", 1_700_000_000_000L), frame)
    }

    @Test
    fun stringEscapingRoundTrips() {
        val original = ControlBye(reason = "quote \" backslash \\ newline \n tab \t unicode \u2603")
        assertEquals(original, roundTrip(original))
    }

    @Test
    fun unknownTypeIsRejected() {
        assertThrows(RuntimeException::class.java) {
            ControlWire.decode("""{"type":"future-frame","foo":1}""")
        }
    }

    @Test
    fun missingTypeIsRejected() {
        assertThrows(RuntimeException::class.java) {
            ControlWire.decode("""{"nonce":"n"}""")
        }
    }

    @Test
    fun missingRequiredFieldIsRejected() {
        assertThrows(RuntimeException::class.java) {
            ControlWire.decode("""{"type":"challenge","nonce":"n"}""")
        }
    }

    @Test
    fun nonObjectRootIsRejected() {
        assertThrows(RuntimeException::class.java) { ControlWire.decode("""[1,2]""") }
        assertThrows(RuntimeException::class.java) { ControlWire.decode("\"challenge\"") }
    }

    @Test
    fun malformedJsonIsRejected() {
        assertThrows(RuntimeException::class.java) { ControlWire.decode("""{"type":"challenge""") }
        assertThrows(RuntimeException::class.java) { ControlWire.decode("""{"type":"ack","requestId":"r","ok":tru}""") }
    }

    @Test
    fun requestSessionActorParsesFromWire() {
        val frame = ControlWire.decode(
            """{"type":"request_session","requestId":"r1","actor":{"userId":"u","userEmail":"e","level":"SUPERUSER"}}"""
        ) as ControlRequestSession
        assertEquals(ControlActor("u", "e", "SUPERUSER"), frame.actor)
        assertNull(frame.expiresAt)
    }

    @Test
    fun encodeOmitsNullOptionalFields() {
        val json = ControlWire.encode(ControlError(code = "x"))
        assertTrue(!json.contains("message") && !json.contains("requestId"))
    }
}