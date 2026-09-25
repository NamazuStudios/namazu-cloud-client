// This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
// If a copy of the MPL was not distributed with this file, You can obtain one at
// https://mozilla.org/MPL/2.0/.

package com.namazustudios.cloud.element.transport

import java.net.URI

/**
 * Transport strategy for a single outbound WebSocket connection. Kept as an interface so channel
 * logic is testable with a fake transport.
 */
interface ControlTransport {
    /** Opens a connection to [url]. A thrown exception is a connect failure (retry, not fallback). */
    fun connect(url: URI, listener: ControlConnectionListener): ControlConnection
}

/**
 * Selects the transport. Per the project decision, Jakarta WebSocket is attempted first: if a
 * `jakarta.websocket` client container is resolvable from this classpath the connection uses it,
 * and it is used for all subsequent connects. If the client API or an implementation is genuinely
 * absent (NoClassDefFoundError / no container), the connector transparently falls back to
 * `java.net.http.WebSocket` — which is why `@ElementPackageRequest("java.net.http")` is declared on
 * the element package.
 */
class WebSocketTransportPicker(
    private val log: (String, Throwable?) -> Unit,
) : ControlTransport {

    private var jakartaUnavailable = false

    override fun connect(url: URI, listener: ControlConnectionListener): ControlConnection {
        if (jakartaAvailable()) {
            try {
                return JakartaWebSocketConnection.connect(url, listener)
            } catch (t: NoClassDefFoundError) {
                // Client API removed between the availability probe and the connect — extremely rare.
                jakartaUnavailable = true
                log("jakarta websocket client API disappeared at connect; falling back to java.net.http", t)
            }
        }
        return JdkWebSocketConnection.connect(url, listener)
    }

    private fun jakartaAvailable(): Boolean {
        if (jakartaUnavailable) return false
        return try {
            if (ContainerProviderHolder.hasContainer()) {
                true
            } else {
                jakartaUnavailable = true
                log("no jakarta websocket container resolvable; using java.net.http transport", null)
                false
            }
        } catch (t: Throwable) {
            jakartaUnavailable = true
            log("jakarta websocket client API unavailable; using java.net.http transport", t)
            false
        }
    }
}

/**
 * Indirection that keeps `jakarta.websocket` types referenced only from this holder, so a runtime
 * that lacks them fails here (caught by [WebSocketTransportPicker]) rather than at class-load time
 * of the picker itself.
 */
private object ContainerProviderHolder {
    fun hasContainer(): Boolean =
        jakarta.websocket.ContainerProvider.getWebSocketContainer() != null
}