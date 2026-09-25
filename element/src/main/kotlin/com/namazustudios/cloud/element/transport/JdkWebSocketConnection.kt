// This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
// If a copy of the MPL was not distributed with this file, You can obtain one at
// https://mozilla.org/MPL/2.0/.

package com.namazustudios.cloud.element.transport

import java.net.URI
import java.net.http.HttpClient
import java.net.http.WebSocket
import java.time.Duration
import java.util.concurrent.CompletionStage
import java.util.concurrent.TimeUnit

/**
 * `java.net.http.WebSocket` transport. Package-private dispatch is deliberately kept to the JDK so
 * no extra runtime dependency is introduced (beyond `@ElementPackageRequest("java.net.http")` on
 * the element package).
 */
class JdkWebSocketConnection private constructor(private val ws: WebSocket) : ControlConnection {

    override fun sendText(text: String) {
        ws.sendText(text, true).join()
    }

    override fun close() {
        try {
            ws.sendClose(WebSocket.NORMAL_CLOSURE, "bye").join()
        } catch (e: Exception) {
            ws.abort()
        }
    }

    override fun abort() {
        ws.abort()
    }

    companion object {
        private const val CONNECT_TIMEOUT_SECONDS = 10L
        private val CLIENT: HttpClient = HttpClient.newHttpClient()

        fun connect(url: URI, listener: ControlConnectionListener): ControlConnection {
            val ws = CLIENT.newWebSocketBuilder()
                .connectTimeout(Duration.ofSeconds(CONNECT_TIMEOUT_SECONDS))
                .buildAsync(url, object : WebSocket.Listener {
                    override fun onOpen(webSocket: WebSocket) {
                        webSocket.request(1)
                    }

                    override fun onText(webSocket: WebSocket, data: CharSequence, last: Boolean): CompletionStage<*>? {
                        listener.onText(data.toString())
                        webSocket.request(1)
                        return null
                    }

                    override fun onClose(webSocket: WebSocket, statusCode: Int, reason: String): CompletionStage<*>? {
                        listener.onClose(statusCode, reason)
                        return null
                    }

                    override fun onError(webSocket: WebSocket, error: Throwable) {
                        listener.onError(error)
                    }
                })
                .get(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            return JdkWebSocketConnection(ws)
        }
    }
}