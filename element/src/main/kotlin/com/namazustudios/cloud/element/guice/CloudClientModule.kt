// This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
// If a copy of the MPL was not distributed with this file, You can obtain one at
// https://mozilla.org/MPL/2.0/.

package com.namazustudios.cloud.element.guice

import com.google.inject.PrivateModule
import com.google.inject.Singleton
import com.namazustudios.cloud.element.persistence.CloudConnectEntityRegistry
import com.namazustudios.cloud.element.persistence.CloudConnectStateDao
import com.namazustudios.cloud.element.service.CloudClientService
import com.namazustudios.cloud.element.service.ControlCommandDispatcher
import com.namazustudios.cloud.element.service.ControlSessionMinter
import com.namazustudios.cloud.element.service.ShadowUserService
import dev.getelements.elements.sdk.dao.EntityRegistry

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
        // The platform instantiates this itself during element load to pre-register our @Entity
        // classes with Morphia, but it must be in the graph for that lookup to resolve.
        bind(EntityRegistry::class.java).to(CloudConnectEntityRegistry::class.java)
        bind(CloudClientService::class.java).asEagerSingleton()
        expose(CloudClientService::class.java)
    }
}