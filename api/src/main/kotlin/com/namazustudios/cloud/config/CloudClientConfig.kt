// This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
// If a copy of the MPL was not distributed with this file, You can obtain one at
// https://mozilla.org/MPL/2.0/.

package com.namazustudios.cloud.config

/**
 * Configuration keys for the Namazu Cloud client element. Keys are dotted element-attribute names
 * (the same ones that appear in `dev.getelements.element.attributes.properties` and map to `@Named`
 * bindings). Each key also has an environment-variable override named per the established proxy
 * convention: `com_namazustudios_cloud_client_url` maps to `com.namazustudios.cloud.client.url`,
 * and so on. Where both are present the environment value wins.
 *
 * These constants live in the protocol artifact so the closed-source control plane can emit the
 * exact same keys when provisioning instances (e.g. injecting `com_namazustudios_cloud_client_*`
 * environment variables at Conductor task launch time, exactly as `conductor-agent` credentials are
 * injected today).
 */
object CloudClientConfig {

    /** Dotted attribute key: the control-plane host the connector dials out to. */
    const val URL = "com.namazustudios.cloud.client.url"

    /** Env override for [URL]. */
    const val URL_ENV = "com_namazustudios_cloud_client_url"

    /** Dotted attribute key: this instance's cloud identifier (`clientId` in the handshake). */
    const val ID = "com.namazustudios.cloud.client.id"

    /** Env override for [ID]. */
    const val ID_ENV = "com_namazustudios_cloud_client_id"

    /** Dotted attribute key: the shared secret (`S`); `K = SHA256(S)` is the HMAC key. */
    const val SECRET = "com.namazustudios.cloud.client.secret"

    /** Env override for [SECRET]. */
    const val SECRET_ENV = "com_namazustudios_cloud_client_secret"

    /** Dotted attribute key: seconds to wait between connection attempts when the channel is down. */
    const val RETRY_SECONDS = "com.namazustudios.cloud.client.retry.seconds"

    /** Env override for [RETRY_SECONDS]. */
    const val RETRY_SECONDS_ENV = "com_namazustudios_cloud_client_retry_seconds"

    /** Dotted attribute key: default lifetime of minted sessions (minutes). */
    const val SESSION_TTL_MINUTES = "com.namazustudios.cloud.client.session.ttl.minutes"

    /** Env override for [SESSION_TTL_MINUTES]. */
    const val SESSION_TTL_MINUTES_ENV = "com_namazustudios_cloud_client_session_ttl_minutes"

    /** Default control-plane host. */
    const val URL_DEFAULT = "cloud.namazustudios.com"

    /** WebSocket sub-path dialed on [URL] (`wss(s)://<url>/control`). */
    const val WS_ROOT = "/control"

    /** Default reconnect delay. */
    const val RETRY_SECONDS_DEFAULT = 5L

    /** Default minted-session lifetime. */
    const val SESSION_TTL_MINUTES_DEFAULT = 60L

    /**
     * Derives the environment override name for a dotted attribute key, e.g.
     * `com.namazustudios.cloud.client.url` -> `com_namazustudios_cloud_client_url`. All-lowercase,
     * mirroring the proxy convention (`com_namazustudios_cloud_*` prefix).
     */
    fun envKeyFor(attributeKey: String): String = attributeKey.replace('.', '_')
}