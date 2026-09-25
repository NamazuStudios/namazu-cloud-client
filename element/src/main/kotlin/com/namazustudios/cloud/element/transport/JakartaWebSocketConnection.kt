// This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
// If a copy of the MPL was not distributed with this file, You can obtain one at
// https://mozilla.org/MPL/2.0/.

package com.namazustudios.cloud.element.transport

import jakarta.websocket.ClientEndpointConfig
import jakarta.websocket.CloseReason
import jakarta.websocket.ContainerProvider
import jakarta.websocket.Endpoint
import jakarta.websocket.EndpointConfig
import jakarta.websocket.Session
import java.net.URI

/**
 * Jakarta WebSocket transport. All `jakarta.websocket` types are confined to this file so its
 * absence at runtime surfaces as a caught [NoClassDefFoundError] in [WebSocketTransportPicker]
 * rather than breaking element startup.
 */
class JakartaWebSocketConnection private constructor(private val session: Session) : ControlConnection {

    override fun sendText(text: String) {
        session.basicRemote.sendText(text)
    }

    override fun close() {
        session.close(CloseReason(CloseReason.CloseCodes.NORMAL_CLOSURE, "bye"))
    }

    override fun abort() {
        session.close(CloseReason(CloseReason.CloseCodes.GOING_AWAY, "aborted"))
    }

    companion object {
        fun connect(url: URI, listener: ControlConnectionListener): ControlConnection {
            val container = ContainerProvider.getWebSocketContainer()
                ?: throw IllegalStateException("jakarta.websocket container not resolvable")
            val config = ClientEndpointConfig.Builder.create().build()
            val session = container.connectToServer(Adapter(listener), config, url)
            return JakartaWebSocketConnection(session)
        }
    }

    /**
     * Programmatic [Endpoint] instance. The annotated-endpoint variant takes a `Class` (the
     * container instantiates it), which can't carry our per-connection [listener]; the programmatic
     * form is passed as an already-constructed instance.
     */
    private class Adapter(private val listener: ControlConnectionListener) : Endpoint() {

        override fun onOpen(session: Session, config: EndpointConfig) {
            session.addMessageHandler(String::class.java) { message -> listener.onText(message) }
        }

        override fun onClose(session: Session?, closeReason: CloseReason?) {
            if (closeReason != null) {
                listener.onClose(closeReason.closeCode.code, closeReason.reasonPhrase)
            }
        }

        override fun onError(session: Session?, error: Throwable?) {
            if (error != null) {
                listener.onError(error)
            }
        }
    }
}