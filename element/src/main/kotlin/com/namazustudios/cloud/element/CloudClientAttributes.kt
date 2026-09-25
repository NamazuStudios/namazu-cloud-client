// This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
// If a copy of the MPL was not distributed with this file, You can obtain one at
// https://mozilla.org/MPL/2.0/.

package com.namazustudios.cloud.element

import com.namazustudios.cloud.config.CloudClientConfig
import dev.getelements.elements.sdk.annotation.ElementDefaultAttribute

/**
 * Element attribute name constants for the cloud-client element. Defaults are declared here so the
 * element boots unconfigured (channel stays idle with a warning); the closed-source control plane
 * provisions real values, and environment-variable overrides (the `com_namazustudios_cloud_*` keys)
 * win over attributes at runtime.
 */
object CloudClientAttributes {

    /** The control-plane host (e.g. `cloud.namazustudios.com`). */
    @ElementDefaultAttribute(CloudClientConfig.URL_DEFAULT)
    const val URL = CloudClientConfig.URL

    /** This instance's cloud identifier — becomes `clientId` in the handshake. Empty = idle. */
    @ElementDefaultAttribute("")
    const val ID = CloudClientConfig.ID

    /** The shared secret `S`. Empty = idle. Must be provisioned out-of-band (env/attribute). */
    @ElementDefaultAttribute("")
    const val SECRET = CloudClientConfig.SECRET

    /** Seconds between connection attempts while the channel is down. */
    @ElementDefaultAttribute(CloudClientConfig.RETRY_SECONDS_DEFAULT.toString())
    const val RETRY_SECONDS = CloudClientConfig.RETRY_SECONDS

    /** Default lifetime of minted sessions (minutes). */
    @ElementDefaultAttribute(CloudClientConfig.SESSION_TTL_MINUTES_DEFAULT.toString())
    const val SESSION_TTL_MINUTES = CloudClientConfig.SESSION_TTL_MINUTES
}