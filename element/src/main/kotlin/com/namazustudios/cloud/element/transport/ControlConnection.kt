// This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
// If a copy of the MPL was not distributed with this file, You can obtain one at
// https://mozilla.org/MPL/2.0/.

package com.namazustudios.cloud.element.transport

/**
 * A minimal transport abstraction over the two WebSocket client implementations the connector can
 * use (Jakarta WebSocket when a container is resolvable, [java.net.http.WebSocket] otherwise). The
 * protocol is one text frame per message.
 */
interface ControlConnection {

    /** Sends a single text frame. Implementations must be safe to call from any thread. */
    fun sendText(text: String)

    /** Closes gracefully (normal closure). */
    fun close()

    /** Aborts immediately without a close handshake. */
    fun abort()
}

/** Receives events from a [ControlConnection]. Callbacks arrive on the transport's threads. */
interface ControlConnectionListener {

    /** A complete text message. */
    fun onText(text: String)

    /** The remote closed, with the close code and reason. */
    fun onClose(status: Int, reason: String)

    /** A transport-level error (connect failure, IO error, protocol violation…). */
    fun onError(error: Throwable)
}