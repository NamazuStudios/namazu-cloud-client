// This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
// If a copy of the MPL was not distributed with this file, You can obtain one at
// https://mozilla.org/MPL/2.0/.

package com.namazustudios.cloud.element.persistence

import dev.getelements.elements.sdk.dao.EntityRegistry

/**
 * Declares this element's Morphia entity classes to the platform. The platform pre-registers
 * these with the `Mapper` — in the element's classloader — before any REST endpoint is served,
 * which is what lets [CloudConnectStateDocument] be queried without a discriminator.
 *
 * Bound in [com.namazustudios.cloud.element.guice.CloudClientModule]; no eager binding is needed
 * because the platform instantiates the registry itself during element load.
 */
class CloudConnectEntityRegistry : EntityRegistry {
    override fun entityClasses(): List<Class<*>> = listOf(
        CloudConnectStateDocument::class.java,
    )
}
