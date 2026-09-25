// This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
// If a copy of the MPL was not distributed with this file, You can obtain one at
// https://mozilla.org/MPL/2.0/.

package com.namazustudios.cloud.element.service

import com.namazustudios.cloud.element.channel.ControlChannel
import com.namazustudios.cloud.protocol.ControlAck
import com.namazustudios.cloud.protocol.ControlBye
import com.namazustudios.cloud.protocol.ControlError
import com.namazustudios.cloud.protocol.ControlErrors
import com.namazustudios.cloud.protocol.ControlFrame
import com.namazustudios.cloud.protocol.ControlRequestSession
import jakarta.inject.Inject
import jakarta.inject.Singleton
import org.slf4j.LoggerFactory

/**
 * Handles authenticated post-handshake frames from the control plane. The only command today is
 * [ControlRequestSession] (mint a session); the reply is sent back on the bound [ControlChannel]
 * (set/unset by [CloudClientService] around each connection).
 */
@Singleton
class ControlCommandDispatcher @Inject constructor(
    private val minter: ControlSessionMinter,
) {
    private val log = LoggerFactory.getLogger(ControlCommandDispatcher::class.java)

    /** The live channel, bound by the service once the handshake completes. */
    @Volatile var channel: ControlChannel? = null

    operator fun invoke(frame: ControlFrame) {
        when (frame) {
            is ControlRequestSession -> handleRequestSession(frame)
            is ControlBye -> channel?.close()
            is ControlError -> log.warn(
                "control plane reported unsolicited error: {} {}",
                frame.code, frame.message ?: "",
            )

            is ControlAck -> log.info("control plane acknowledged: request={} ok={}", frame.requestId, frame.ok)
            else -> log.warn("received unrecognized authenticated frame type: {}", frame.type)
        }
    }

    private fun handleRequestSession(request: ControlRequestSession) {
        val reply: ControlFrame = try {
            minter.mint(request.actor, request.expiresAt).copy(requestId = request.requestId)
        } catch (e: Exception) {
            log.warn("failed to mint control session for actor {}: {}", request.actor.userId, e.message)
            ControlError(ControlErrors.SESSION_MINT_FAILED, e.message, request.requestId)
        }
        val active = channel
        if (active == null) {
            log.warn("dropping reply for request {} — channel is gone", request.requestId)
        } else {
            active.send(reply)
        }
    }
}