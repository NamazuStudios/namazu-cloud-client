// This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
// If a copy of the MPL was not distributed with this file, You can obtain one at
// https://mozilla.org/MPL/2.0/.

package com.namazustudios.cloud.element.guice

import com.google.inject.PrivateModule
import com.google.inject.Singleton
import com.namazustudios.cloud.element.service.CloudClientService
import com.namazustudios.cloud.element.service.ControlCommandDispatcher
import com.namazustudios.cloud.element.service.ControlSessionMinter
import com.namazustudios.cloud.element.service.ShadowUserService

/**
 * Wires the cloud-client element. [CloudClientService] is an eager singleton so the outbound
 * channel starts dialing when the element loads; everything else binds in the element's private
 * injector and only [CloudClientService] is exposed (so sibling elements could query its state).
 */
class CloudClientModule : PrivateModule() {

    override fun configure() {
        bind(ShadowUserService::class.java).`in`(Singleton::class.java)
        bind(ControlSessionMinter::class.java).`in`(Singleton::class.java)
        bind(ControlCommandDispatcher::class.java).`in`(Singleton::class.java)
        bind(CloudClientService::class.java).asEagerSingleton()
        expose(CloudClientService::class.java)
    }
}