// This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
// If a copy of the MPL was not distributed with this file, You can obtain one at
// https://mozilla.org/MPL/2.0/.

package com.namazustudios.cloud.mock

import com.namazustudios.cloud.config.CloudClientConfig
import com.namazustudios.cloud.protocol.ControlActor
import com.namazustudios.cloud.protocol.ControlBye
import com.namazustudios.cloud.protocol.ControlChallenge
import com.namazustudios.cloud.protocol.ControlError
import com.namazustudios.cloud.protocol.ControlMac
import com.namazustudios.cloud.protocol.ControlRequestSession
import com.namazustudios.cloud.protocol.ControlResponse
import com.namazustudios.cloud.protocol.ControlServerAuth
import com.namazustudios.cloud.protocol.ControlSession
import com.namazustudios.cloud.protocol.ControlWire
import dev.getelements.elements.sdk.ElementSupplier
import jakarta.websocket.OnClose
import jakarta.websocket.OnMessage
import jakarta.websocket.OnOpen
import jakarta.websocket.Session
import jakarta.websocket.server.ServerEndpoint
import org.slf4j.LoggerFactory

/**
 * Development-only stand-in for the Namazu Cloud control plane. Served by the SDK-local runtime at
 * `ws://localhost:<port>/control`, it runs the real server side of the mutual-HMAC handshake
 * against the client element and then issues a loopback `request_session`, printing the minted
 * session secret when the client replies. NEVER deployed.
 *
 * The shared secret is read from the same attribute the client element uses
 * ([CloudClientConfig.SECRET]); in the debug harness it is provisioned on this element's package
 * path. Defaults to [DEFAULT_SECRET] so the stock harness works out of the box.
 */
@ServerEndpoint(CloudClientConfig.WS_ROOT)
class MockControlPlaneEndpoint {

    private val log = LoggerFactory.getLogger(MockControlPlaneEndpoint::class.java)

    @OnOpen
    fun onOpen(session: Session) {
        val key = ControlMac.deriveKey(secret())
        val challenge = ControlChallenge(ControlMac.newNonce(), System.currentTimeMillis() + 30_000L)
        session.userProperties[KEY] = key
        session.userProperties[CHALLENGE] = challenge
        session.basicRemote.sendText(ControlWire.encode(challenge))
        log.info("[mock] challenge sent to session {}", session.id)
    }

    @OnMessage
    fun onMessage(message: String, session: Session) {
        if (session.userProperties[AUTHED] == true) {
            handleAuthenticated(message, session)
            return
        }

        val key = session.userProperties[KEY] as ByteArray
        val challenge = session.userProperties[CHALLENGE] as ControlChallenge
        val frame = try {
            ControlWire.decode(message)
        } catch (e: RuntimeException) {
            return reject(session, "undecodable client frame: ${e.message}")
        }

        val response = frame as? ControlResponse
            ?: return reject(session, "expected response, got ${frame.type}")
        if (!ControlMac.validate(
                key, response.mac, ControlMac.LABEL_CLIENT_AUTH, response.clientId, response.challengeNonce,
            )
        ) {
            return reject(session, "client MAC mismatch for clientId=${response.clientId}")
        }

        session.userProperties[AUTHED] = true
        val mac = ControlMac.compute(
            key, ControlMac.LABEL_SERVER_AUTH, response.clientId, response.challengeNonce, response.nonceClient,
        )
        session.basicRemote.sendText(ControlWire.encode(ControlServerAuth(mac)))

        // Immediately request a session. Socket ordering guarantees the client verifies the
        // server-auth frame before it sees this one.
        val request = ControlRequestSession(
            requestId = "loopback-${nextRequestId()}",
            actor = ControlActor("cloud-user-1", "patrick@namazustudios.com", "SUPERUSER"),
        )
        session.userProperties[REQUEST_ID] = request.requestId
        session.basicRemote.sendText(ControlWire.encode(request))
        log.info("[mock] requested loopback session {}", request.requestId)
    }

    private fun handleAuthenticated(message: String, session: Session) {
        val frame = try {
            ControlWire.decode(message)
        } catch (e: RuntimeException) {
            println("[mock] CLIENT SENT UNDECODABLE FRAME: ${e.message}")
            session.close()
            return
        }
        when (frame) {
            is ControlSession -> {
                val expected = session.userProperties[REQUEST_ID] as String?
                val requestIdOk = expected == null || expected == frame.requestId
                if (frame.sessionSecret.isNotBlank() && requestIdOk) {
                    println(">>>> [mock] LOOPBACK OK: control session minted for ${frame.userName} (requestId=${frame.requestId})")
                } else {
                    println("[mock] LOOPBACK FAILED: session frame did not echo requestId or had empty secret")
                }
                session.close()
            }

            is ControlError -> {
                println("[mock] CLIENT RETURNED ERROR ${frame.code}: ${frame.message}")
                session.close()
            }

            is ControlBye -> log.info("[mock] client sent bye: {}", frame.reason)

            else -> log.warn("[mock] unusual frame from client: {}", frame.type)
        }
    }

    @OnClose
    fun onClose(session: Session) {
        log.info("[mock] session {} closed", session.id)
    }

    private fun reject(session: Session, reason: String) {
        log.warn("[mock] rejecting session {}: {}", session.id, reason)
        session.basicRemote.sendText(
            ControlWire.encode(ControlError(code = "unauthorized", message = reason)),
        )
        session.close()
    }

    private fun secret(): String {
        val element = ElementSupplier.getElementLocal(MockControlPlaneEndpoint::class.java).get()
        val attrs = element.elementRecord.attributes()
        return attrs.getAttributeOptional(CloudClientConfig.SECRET)
            .map { it.toString() }
            .orElse(DEFAULT_SECRET)
    }

    companion object {
        private const val DEFAULT_SECRET = "dev-secret"

        private const val KEY = "mock.key"
        private const val CHALLENGE = "mock.challenge"
        private const val AUTHED = "mock.authed"
        private const val REQUEST_ID = "mock.requestId"

        @Volatile private var requestCounter = 0L

        private fun nextRequestId(): Long = synchronized(this) { ++requestCounter }
    }
}