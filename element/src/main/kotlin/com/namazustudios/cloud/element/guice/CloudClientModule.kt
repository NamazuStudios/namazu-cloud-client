// This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
// If a copy of the MPL was not distributed with this file, You can obtain one at
// https://mozilla.org/MPL/2.0/.

package com.namazustudios.cloud.element.guice

import com.google.inject.PrivateModule
import com.google.inject.Singleton
import com.namazustudios.cloud.element.persistence.CloudConnectStateDao
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
        // Backs the dashboard's persisted on/off switch. Never throws into the channel lifecycle —
        // a database outage degrades to in-memory-only state rather than taking the connector down.
        bind(CloudConnectStateDao::class.java).`in`(Singleton::class.java)
        // CloudConnectEntityRegistry carries @ElementServiceExport(EntityRegistry::class), which the
        // loader's GuiceSpiModule binds AND exposes automatically. Binding it here as well fails the
        // whole element at load time with Guice/BindingAlreadySet - and the element is then skipped
        // silently ("Caught exception loading element. Skipping."), so never re-bind exported services.
        bind(CloudClientService::class.java).asEagerSingleton()
        expose(CloudClientService::class.java)
    }
}