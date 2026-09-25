// This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
// If a copy of the MPL was not distributed with this file, You can obtain one at
// https://mozilla.org/MPL/2.0/.

package com.namazustudios.cloud.element.channel

import com.namazustudios.cloud.element.transport.ControlConnection
import com.namazustudios.cloud.element.transport.ControlConnectionListener
import com.namazustudios.cloud.element.transport.ControlTransport
import com.namazustudios.cloud.protocol.ControlActor
import com.namazustudios.cloud.protocol.ControlChallenge
import com.namazustudios.cloud.protocol.ControlFrame
import com.namazustudios.cloud.protocol.ControlMac
import com.namazustudios.cloud.protocol.ControlProtocolException
import com.namazustudios.cloud.protocol.ControlRequestSession
import com.namazustudios.cloud.protocol.ControlResponse
import com.namazustudios.cloud.protocol.ControlServerAuth
import com.namazustudios.cloud.protocol.ControlWire
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.URI
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit

class ControlChannelTest {

    private val clientId = "instance-42"
    private val key = ControlMac.deriveKey("top-secret")
    private val url = URI.create("ws://localhost/control")

    /** Records what the client actually wrote to the socket. */
    private class FakeConnection(val listener: ControlConnectionListener) : ControlConnection {
        val sent = mutableListOf<String>()
        var closed = false
        var aborted = false
        override fun sendText(text: String) {
            sent += text
        }

        override fun close() {
            closed = true
        }

        override fun abort() {
            aborted = true
        }
    }

    private class FakeTransport : ControlTransport {
        var connection: FakeConnection? = null
        override fun connect(url: URI, listener: ControlConnectionListener): ControlConnection {
            val c = FakeConnection(listener)
            connection = c
            return c
        }
    }

    private fun challenge(nonce: String = ControlMac.newNonce()): ControlChallenge =
        ControlChallenge(nonce, System.currentTimeMillis() + 60_000L)

    private fun handshakeResponses(conn: FakeConnection, c: ControlChallenge): ControlResponse {
        conn.listener.onText(ControlWire.encode(c))
        val response = ControlWire.decode(conn.sent.last()) as ControlResponse
        assertEquals(clientId, response.clientId)
        assertEquals(c.nonce, response.challengeNonce)
        assertTrue(ControlMac.validate(key, response.mac, ControlMac.LABEL_CLIENT_AUTH, clientId, c.nonce))
        return response
    }

    private fun completeServerAuth(conn: FakeConnection, c: ControlChallenge, r: ControlResponse) {
        conn.listener.onText(
            ControlWire.encode(
                ControlServerAuth(ControlMac.compute(key, ControlMac.LABEL_SERVER_AUTH, clientId, c.nonce, r.nonceClient))
            )
        )
    }

    @Test
    fun completesHandshakeAndAuthenticates() {
        val transport = FakeTransport()
        val delivered = mutableListOf<ControlFrame>()
        val channel = ControlChannel(clientId, key, url, transport, { _, _ -> }, { delivered += it })

        val open = channel.open()
        val c = challenge()
        val r = handshakeResponses(transport.connection!!, c)
        completeServerAuth(transport.connection!!, c, r)

        assertTrue(open.get(2, TimeUnit.SECONDS) == null)
        assertFalse(channel.awaitTerminal().isDone)

        // Post-auth the channel can send and dispatches authenticated frames to the handler.
        channel.send(ControlRequestSession("req-1", ControlActor("u1", "e1", "SUPERUSER")))
        val request = ControlRequestSession("req-2", ControlActor("u2", "e2", "SUPERUSER"))
        transport.connection!!.listener.onText(ControlWire.encode(request))
        assertEquals(listOf(request), delivered)

        channel.close()
        assertTrue(transport.connection!!.closed)
        assertTrue(channel.awaitTerminal().get(2, TimeUnit.SECONDS) == null)
    }

    @Test
    fun rejectsBadServerAuth() {
        val transport = FakeTransport()
        val channel = ControlChannel(clientId, key, url, transport, { _, _ -> }, {})
        val open = channel.open()
        val c = challenge()
        val r = handshakeResponses(transport.connection!!, c)

        // Tampered MAC — a different server (or a compromised channel) must not pass.
        transport.connection!!.listener.onText(
            ControlWire.encode(ControlServerAuth("0".repeat(64)))
        )

        val e = assertThrows(ExecutionException::class.java) { open.get(2, TimeUnit.SECONDS) }
        assertTrue(e.cause is ControlProtocolException)
        assertTrue(transport.connection!!.aborted, "bad server-auth must tear down the socket")
    }

    @Test
    fun rejectsExpiredChallenge() {
        val transport = FakeTransport()
        val channel = ControlChannel(clientId, key, url, transport, { _, _ -> }, {})
        val open = channel.open()
        transport.connection!!.listener.onText(
            ControlWire.encode(ControlChallenge(ControlMac.newNonce(), System.currentTimeMillis() - 1))
        )
        val e = assertThrows(ExecutionException::class.java) { open.get(2, TimeUnit.SECONDS) }
        assertTrue(e.cause is ControlProtocolException)
        assertTrue(transport.connection!!.sent.isEmpty(), "no response may be sent for an expired challenge")
    }

    @Test
    fun rejectsWrongFrameDuringHandshake() {
        val transport = FakeTransport()
        val channel = ControlChannel(clientId, key, url, transport, { _, _ -> }, {})
        val open = channel.open()
        // Server talks out of turn — a session frame before any handshake.
        transport.connection!!.listener.onText(
            ControlWire.encode(ControlRequestSession("req-0", ControlActor("a", "b", "SUPERUSER")))
        )
        assertThrows(ExecutionException::class.java) { open.get(2, TimeUnit.SECONDS) }
    }

    @Test
    fun cannotSendBeforeAuthentication() {
        val transport = FakeTransport()
        val channel = ControlChannel(clientId, key, url, transport, { _, _ -> }, {})
        channel.open()
        assertThrows(IllegalStateException::class.java) {
            channel.send(ControlRequestSession("req-0", ControlActor("a", "b", "SUPERUSER")))
        }
        assertTrue(transport.connection!!.sent.isEmpty(), "nothing may be written before authentication")
    }

    @Test
    fun connectFailurePropagatesToOpen() {
        object : ControlTransport {
            override fun connect(url: URI, listener: ControlConnectionListener): ControlConnection {
                throw java.io.IOException("connection refused")
            }
        }.let { transport ->
            val channel = ControlChannel(clientId, key, url, transport, { _, _ -> }, {})
            val open = channel.open()
            val e = assertThrows(ExecutionException::class.java) { open.get(2, TimeUnit.SECONDS) }
            assertTrue(e.cause is java.io.IOException)
        }
    }
}