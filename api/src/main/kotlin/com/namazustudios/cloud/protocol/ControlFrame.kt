// This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
// If a copy of the MPL was not distributed with this file, You can obtain one at
// https://mozilla.org/MPL/2.0/.

package com.namazustudios.cloud.protocol

/**
 * A protocol-level failure on the control channel: malformed frame, bad MAC, unknown peer, or a
 * state-machine violation. Thrown/recorded by channel implementations and the control plane;
 * distinguishable from transport failures.
 */
class ControlProtocolException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/**
 * A single frame on the cloud-control WebSocket channel. Every frame is a JSON object whose
 * `type` field discriminates the payload; see [ControlWire] for the wire format.
 *
 * These types are plain Kotlin data classes on purpose — the protocol artifact stays free of any
 * serialization framework so both the (open-source) client element and the closed-source control
 * plane can bundle it without dragging Jackson (or a Kotlin Jackson module) into classpaths where
 * it is problematic.
 */
sealed interface ControlFrame {

    /** The `type` discriminator. Advances the channel state machine on both ends. */
    val type: String

    companion object {
        const val TYPE_CHALLENGE = "challenge"
        const val TYPE_RESPONSE = "response"
        const val TYPE_SERVER_AUTH = "server-auth"
        const val TYPE_REQUEST_SESSION = "request_session"
        const val TYPE_SESSION = "session"
        const val TYPE_ERROR = "error"
        const val TYPE_ACK = "ack"
        const val TYPE_BYE = "bye"
    }
}

/**
 * The cloud user this session is being minted for. The client element ensures a shadow superuser
 * exists for `userId`/`userEmail` before minting. `level` is a plain string (e.g. `"SUPERUSER"`)
 * so the protocol artifact stays independent of the Elements SDK model.
 */
data class ControlActor(
    val userId: String,
    val userEmail: String,
    val level: String,
)

/**
 * Frame 1 of the mutual handshake (server -> client). The client must respond within
 * [expiresAt] (epoch millis) or the server will forget the challenge.
 */
data class ControlChallenge(
    val nonce: String,
    val expiresAt: Long,
) : ControlFrame {
    override val type: String get() = ControlFrame.TYPE_CHALLENGE
}

/**
 * Frame 2 of the mutual handshake (client -> server). `mac` proves knowledge of the shared
 * secret:
 *
 * ```
 * mac = HMAC_SHA256(K, "client-auth.v1." + clientId + "." + challengeNonce)
 * ```
 *
 * where `K = SHA256(sharedSecret)` and `challengeNonce` is echoed back from the server's
 * [ControlChallenge] so the server can verify statelessly and a replay of an old challenge cannot
 * be answered.
 */
data class ControlResponse(
    val clientId: String,
    val challengeNonce: String,
    val nonceClient: String,
    val mac: String,
) : ControlFrame {
    override val type: String get() = ControlFrame.TYPE_RESPONSE
}

/**
 * Frame 3 of the mutual handshake (server -> client). Proves the server knows the shared secret
 * before the client will mint any sessions:
 *
 * ```
 * mac = HMAC_SHA256(K, "server-auth.v1." + clientId + "." + challengeNonce + "." + nonceClient)
 * ```
 */
data class ControlServerAuth(
    val mac: String,
) : ControlFrame {
    override val type: String get() = ControlFrame.TYPE_SERVER_AUTH
}

/**
 * Sentinel frame in the handshake when the client uses a preconfigured shared secret to make its
 * first contact instead of waiting for a challenge (never used by the reference connector today;
 * reserved so probes and policy-based flows don't need wire-format changes).
 */
data class ControlBye(
    val reason: String? = null,
) : ControlFrame {
    override val type: String get() = ControlFrame.TYPE_BYE
}

/**
 * Post-auth request (cloud -> client): mint a session for [actor] and return it. `requestId`
 * correlates the [ControlSession] / [ControlError] reply. `expiresAt` (epoch millis) optionally
 * bounds the session lifetime; when null the client's default TTL applies.
 */
data class ControlRequestSession(
    val requestId: String,
    val actor: ControlActor,
    val expiresAt: Long? = null,
) : ControlFrame {
    override val type: String get() = ControlFrame.TYPE_REQUEST_SESSION
}

/**
 * Post-auth reply (client -> cloud): a freshly minted `Elements-SessionSecret` plus the
 * instance-side identity of the shadow superuser the session belongs to.
 */
data class ControlSession(
    val requestId: String,
    val sessionSecret: String,
    val expiresAt: Long? = null,
    val userId: String? = null,
    val userName: String? = null,
) : ControlFrame {
    override val type: String get() = ControlFrame.TYPE_SESSION
}

/**
 * An error reply. When carrying the `requestId` of a request it is the negative response to that
 * request; a `requestId`-less error is an unsolicited fatal condition.
 */
data class ControlError(
    val code: String,
    val message: String? = null,
    val requestId: String? = null,
) : ControlFrame {
    override val type: String get() = ControlFrame.TYPE_ERROR
}

/**
 * Acknowledges a request that has no other response (currently reserved for future commands).
 */
data class ControlAck(
    val requestId: String,
    val ok: Boolean,
) : ControlFrame {
    override val type: String get() = ControlFrame.TYPE_ACK
}

/**
 * Well-known error codes carried by [ControlError].
 */
object ControlErrors {
    const val CHALLENGE_EXPIRED = "challenge_expired"
    const val UNAUTHORIZED = "unauthorized"
    const val MALFORMED = "malformed"
    const val UNSUPPORTED = "unsupported"
    const val INTERNAL = "internal"
    const val SESSION_MINT_FAILED = "session_mint_failed"
    const val CLOSED = "closed"
}