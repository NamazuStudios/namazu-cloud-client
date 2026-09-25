// This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
// If a copy of the MPL was not distributed with this file, You can obtain one at
// https://mozilla.org/MPL/2.0/.

package com.namazustudios.cloud.element.channel

import com.namazustudios.cloud.element.transport.ControlConnection
import com.namazustudios.cloud.element.transport.ControlConnectionListener
import com.namazustudios.cloud.element.transport.ControlTransport
import com.namazustudios.cloud.protocol.ControlAck
import com.namazustudios.cloud.protocol.ControlBye
import com.namazustudios.cloud.protocol.ControlChallenge
import com.namazustudios.cloud.protocol.ControlError
import com.namazustudios.cloud.protocol.ControlFrame
import com.namazustudios.cloud.protocol.ControlMac
import com.namazustudios.cloud.protocol.ControlProtocolException
import com.namazustudios.cloud.protocol.ControlResponse
import com.namazustudios.cloud.protocol.ControlServerAuth
import com.namazustudios.cloud.protocol.ControlWire
import java.net.URI
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * The per-connection control-channel state machine. Runs the single-exchange mutual HMAC handshake
 * and, once authenticated, forwards frames to [frameHandler].
 *
 * ```
 * server -> (1) challenge     {nonce, expiresAt}
 * client -> (2) response      {clientId, challengeNonce, nonceClient, mac}
 * server -> (3) server-auth   {mac}                    <- client must verify before acting
 *                 ... authenticated frames only from here on ...
 * cloud   -> request_session  {requestId, actor, expiresAt?}
 * client  -> session          {requestId, sessionSecret, ...}   | error | ack | bye
 * ```
 *
 * The handshake is performed once per connection and never re-run mid-connection; neither side
 * sends further MACs per frame.
 */
class ControlChannel(
    private val clientId: String,
    private val key: ByteArray,
    private val url: URI,
    private val transport: ControlTransport,
    private val log: (String, Throwable?) -> Unit,
    private val frameHandler: (ControlFrame) -> Unit,
    private val onAuthenticated: (ControlChannel) -> Unit = {},
) : ControlConnectionListener {

    companion object {
        private const val HANDSHAKE_TIMEOUT_SECONDS = 30L
    }

    private enum class State { CONNECTING, AUTHENTICATING, AUTHENTICATED, CLOSED }

    @Volatile private var state: State = State.CONNECTING
    @Volatile private var connection: ControlConnection? = null
    @Volatile private var challenge: ControlChallenge? = null
    @Volatile private var nonceClient: String = ""
    @Volatile private var closedByUs = false

    private val authFuture = CompletableFuture<Void>()
    private val terminalFuture = CompletableFuture<Void>()

    /**
     * Opens the connection and completes the mutual handshake. The returned future completes
     * exceptionally on connect failure, handshake failure, or timeout; callers bound it with a
     * timeout so a silent server can't hang the loop.
     */
    fun open(): CompletableFuture<Void> {
        try {
            connection = transport.connect(url, this)
        } catch (t: Throwable) {
            authFuture.completeExceptionally(t)
            return authFuture
        }
        authFuture.orTimeout(HANDSHAKE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .whenComplete { _, err -> if (err != null) abort() }
        return authFuture
    }

    /** Completes when the channel has reached a closed state. */
    fun awaitTerminal(): CompletableFuture<Void> = terminalFuture

    /**
     * Sends an authenticated frame. Only valid while authenticated; the dispatcher guards this via
     * the channel being bound to it only after [open] completes.
     */
    fun send(frame: ControlFrame) {
        check(state == State.AUTHENTICATED) { "attempted send while channel is ${state}" }
        connection?.sendText(ControlWire.encode(frame))
    }

    /** Pre-auth abort (e.g. the service timed out the handshake). */
    fun abort() = disconnect(cause = null)

    /** Graceful close: bye, then close the socket. */
    fun close() {
        if (state == State.AUTHENTICATED) {
            runCatching { connection?.sendText(ControlWire.encode(ControlBye("client shutdown"))) }
        }
        connection?.let { runCatching { it.close() } }
        disconnect(cause = null)
    }

    // ---------------------------------------------------------------- listener callbacks

    override fun onText(text: String) {
        val frame = try {
            ControlWire.decode(text)
        } catch (e: RuntimeException) {
            protocolError(frame = null, reason = "undecodable frame: ${e.message}")
            return
        }
        when (state) {
            State.CONNECTING -> onChallenge(frame)
            State.AUTHENTICATING -> onServerAuth(frame)
            State.AUTHENTICATED -> onAuthenticated(frame)
            State.CLOSED -> Unit
        }
    }

    override fun onClose(status: Int, reason: String) {
        if (!closedByUs) {
            log("control channel closed by peer (status=$status, reason=$reason)", null)
        }
        disconnect(cause = null)
    }

    override fun onError(error: Throwable) {
        log("control channel transport error", error)
        disconnect(cause = error)
    }

    // ---------------------------------------------------------------- handshake transitions

    private fun onChallenge(frame: ControlFrame) {
        val challengeFrame = frame as? ControlChallenge
            ?: return protocolError(frame, "expected challenge, got ${frame.type}")
        if (challengeFrame.expiresAt <= System.currentTimeMillis()) {
            return protocolError(frame, "rejected expired challenge")
        }
        nonceClient = ControlMac.newNonce()
        challenge = challengeFrame
        state = State.AUTHENTICATING
        val mac = ControlMac.compute(key, ControlMac.LABEL_CLIENT_AUTH, clientId, challengeFrame.nonce)
        connection?.sendText(
            ControlWire.encode(ControlResponse(clientId, challengeFrame.nonce, nonceClient, mac))
        )
    }

    private fun onServerAuth(frame: ControlFrame) {
        if (frame is ControlError) {
            log("control plane rejected the handshake: ${frame.code}${frame.message?.let { " — $it" } ?: ""}", null)
            return disconnect(cause = ControlProtocolException("handshake rejected: ${frame.code}"))
        }
        val serverAuth = frame as? ControlServerAuth
            ?: return protocolError(frame, "expected server-auth, got ${frame.type}")
        val challengeFrame = challenge
            ?: return protocolError(frame, "server-auth received before challenge")
        if (!ControlMac.validate(key, serverAuth.mac, ControlMac.LABEL_SERVER_AUTH, clientId, challengeFrame.nonce, nonceClient)) {
            return protocolError(
                frame,
                "server-auth MAC mismatch — connection to a different/compromised control plane?",
            )
        }
        state = State.AUTHENTICATED
        authFuture.complete(null)
        onAuthenticated(this)
    }

    private fun onAuthenticated(frame: ControlFrame) {
        when (frame) {
            is ControlBye -> {
                log("control plane closed the channel (${frame.reason ?: "no reason provided"})", null)
                close()
            }

            is ControlError -> log("control plane reported error: ${frame.code}${frame.message?.let { " — $it" } ?: ""}", null)
            is ControlAck -> log("control plane acknowledged request ${frame.requestId} ok=${frame.ok}", null)
            else -> frameHandler(frame)
        }
    }

    // ---------------------------------------------------------------- termination helpers

    private fun protocolError(frame: ControlFrame?, reason: String) {
        log("protocol violation: $reason", null)
        disconnect(cause = ControlProtocolException(reason))
    }

    private fun disconnect(cause: Throwable?) {
        if (state == State.CLOSED) return
        state = State.CLOSED
        closedByUs = true
        connection?.let { runCatching { it.abort() } }
        connection = null
        if (!authFuture.isDone) {
            authFuture.completeExceptionally(cause ?: ControlProtocolException("channel closed before authentication"))
        }
        if (!terminalFuture.isDone) terminalFuture.complete(null)
    }
}