// This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
// If a copy of the MPL was not distributed with this file, You can obtain one at
// https://mozilla.org/MPL/2.0/.

package com.namazustudios.cloud.element.rest

import com.namazustudios.cloud.element.service.CloudClientService
import com.namazustudios.cloud.element.service.CloudConnectState

/**
 * Wire types for the "Namazu Cloud Connect" dashboard page. These are the exact shapes the
 * superuser plugin bundle in `ui/src/superuser` consumes.
 */

/** One resolved configuration value plus the layer it came from. */
data class ParameterValueDto(
    /** The attribute key, e.g. `com.namazustudios.cloud.client.url`. */
    val key: String,
    /** The value actually in effect, after env-var precedence. */
    val value: String,
    /** `ENVIRONMENT` when the env override won, `ATTRIBUTE` when the element attribute did. */
    val source: String,
    /** The environment variable that overrides this key, e.g. `com_namazustudios_cloud_client_url`. */
    val envKey: String,
    /** `true` when the env var is actually set on this process. */
    val overriddenByEnvironment: Boolean,
)

/**
 * The read-only parameter readout rendered under the status light. The shared secret is included
 * masked and only when one is configured — the plaintext value is served exclusively by
 * `GET /connect/secret`.
 */
data class ConnectParametersDto(
    val url: String,
    /** The fully-expanded WebSocket URI, including the `/control` root that gets appended. */
    val controlUri: String,
    val clientId: String,
    /** A fixed-width mask, or the empty string when no secret is configured. */
    val secret: String,
    val secretConfigured: Boolean,
    val retrySeconds: Long,
    val sessionTtlMinutes: Long,
    val values: List<ParameterValueDto>,
)

/** The full status payload: live channel state, the operator's intent, and the parameters. */
data class ConnectStatusDto(
    /** The operator's persisted on/off preference. */
    val enabled: Boolean,
    /** `CONNECTED` / `CONNECTING` / `DISCONNECTED` / `NOT_CONFIGURED` / `DISABLED`. */
    val state: String,
    /** `true` only while the mutual handshake has completed and the channel is serving. */
    val connected: Boolean,
    val detail: String,
    val lastConnectedAt: Long?,
    val lastDisconnectedAt: Long?,
    val lastError: String?,
    val updatedAt: Long?,
    val parameters: ConnectParametersDto,
)

/** Request body for `POST /connect/enabled`. */
data class SetEnabledRequest(
    val enabled: Boolean = false,
)

/** Response body for `GET /connect/secret`. Superuser-only; never logged. */
data class RevealSecretDto(
    val secret: String,
)

internal fun CloudConnectState.detail(): String = when (this) {
    CloudConnectState.CONNECTED ->
        "Authenticated with Namazu Cloud. Remote control is live."
    CloudConnectState.CONNECTING ->
        "Dialing the control plane and running the mutual-auth handshake."
    CloudConnectState.DISCONNECTED ->
        "Not connected to Namazu Cloud. Retrying in the background."
    CloudConnectState.NOT_CONFIGURED ->
        "Client id and/or shared secret are not set, so no connection is attempted."
    CloudConnectState.DISABLED ->
        "Turned off by a superuser. Namazu Cloud has no channel to this instance."
}
