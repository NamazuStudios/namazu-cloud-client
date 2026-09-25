// This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
// If a copy of the MPL was not distributed with this file, You can obtain one at
// https://mozilla.org/MPL/2.0/.

package com.namazustudios.cloud.element.persistence

import dev.morphia.annotations.Entity
import dev.morphia.annotations.Id

/**
 * Singleton Morphia entity holding the durable state of the Namazu Cloud Connect channel.
 *
 * Two distinct concerns are persisted here, and they are deliberately NOT the same field:
 *
 *  - [enabled] is **operator intent** — "should this instance be talking to Namazu Cloud at all?".
 *    It is written only by an explicit superuser action and is the value read back at element
 *    start to decide whether to dial out. Default is `true`, so the connector keeps today's
 *    always-on behaviour unless somebody deliberately turns it off.
 *
 *  - [connected] and the timestamps are **observed live state** — written on every channel
 *    transition. A persisted `connected = true` can go stale if the process is killed without
 *    writing on the way out, so [CloudConnectStateDao] reconciles it at start-up: the first
 *    transition after boot overwrites it with the truth. It is for operator display and debugging,
 *    not for control flow.
 *
 * Exactly one document exists per deployment, keyed by [DOC_ID].
 */
@Entity("cloud_connect_state", useDiscriminator = false)
class CloudConnectStateDocument {

    @Id
    var id: String = DOC_ID

    /** Operator intent. `true` unless a superuser has explicitly disabled the connector. */
    var enabled: Boolean = true

    /** Last observed live state of the control channel. See the class docs for staleness. */
    var connected: Boolean = false

    /** Last observed state name (mirrors `CloudConnectStatus`), for display without the element. */
    var state: String = "DISCONNECTED"

    /** Epoch millis of the last successful mutual-auth handshake; null until first connect. */
    var lastConnectedAt: Long? = null

    /** Epoch millis of the last time the channel left the connected state; null until first drop. */
    var lastDisconnectedAt: Long? = null

    /** Message from the last failed connect/handshake, if any. Never contains the shared secret. */
    var lastError: String? = null

    /** Epoch millis of the last write to this document. */
    var updatedAt: Long = 0L

    companion object {
        const val DOC_ID = "cloud_connect_state"
    }
}
