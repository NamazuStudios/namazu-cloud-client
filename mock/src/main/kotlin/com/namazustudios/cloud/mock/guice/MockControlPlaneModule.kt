// This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
// If a copy of the MPL was not distributed with this file, You can obtain one at
// https://mozilla.org/MPL/2.0/.

package com.namazustudios.cloud.mock.guice

import com.google.inject.PrivateModule

/**
 * Binds nothing beyond the module the loader requires; the mock control plane is pure WebSocket
 * endpoint behavior and reads its shared secret from the element attributes.
 */
class MockControlPlaneModule : PrivateModule() {

    override fun configure() {
        // The @ServerEndpoint class is discovered and instantiated by the platform's
        // JakartaWebsocketLoader; attribute injection is resolved there via ElementSupplier.
    }
}