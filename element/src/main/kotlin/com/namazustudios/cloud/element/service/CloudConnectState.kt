// This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
// If a copy of the MPL was not distributed with this file, You can obtain one at
// https://mozilla.org/MPL/2.0/.

package com.namazustudios.cloud.element.service

/**
 * The observable state of the Namazu Cloud Connect control channel, as surfaced by the superuser
 * dashboard and the `GET /connect` endpoint. The [wireName] is what is persisted and what the
 * dashboard renders, so it is deliberately decoupled from the Kotlin enum constant name.
 */
enum class CloudConnectState(val wireName: String) {

    /** A superuser has switched the connector off. No socket is opened and none is attempted. */
    DISABLED("DISABLED"),

    /** The client id and/or shared secret is blank, so there is nothing to authenticate with. */
    NOT_CONFIGURED("NOT_CONFIGURED"),

    /** A connection attempt is in flight, or the mutual HMAC handshake has not completed yet. */
    CONNECTING("CONNECTING"),

    /** The mutual handshake completed and the channel is authenticated and serving requests. */
    CONNECTED("CONNECTED"),

    /**
     * Not currently authenticated. This covers both "never connected yet" and "dropped, will
     * retry" — the retry delay is visible separately as `retrySeconds`, and the cause (if any) is
     * carried by [CloudClientService.lastError] rather than by a distinct enum value.
     */
    DISCONNECTED("DISCONNECTED"),
    ;

    override fun toString(): String = wireName

    companion object {
        /** Parses a [wireName] back into a state, falling back to [DISCONNECTED] for unknown input. */
        fun fromWireName(name: String?): CloudConnectState =
            entries.firstOrNull { it.wireName == name } ?: DISCONNECTED
    }
}
