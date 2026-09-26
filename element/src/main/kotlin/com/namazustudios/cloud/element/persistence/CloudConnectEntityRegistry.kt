// This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
// If a copy of the MPL was not distributed with this file, You can obtain one at
// https://mozilla.org/MPL/2.0/.

package com.namazustudios.cloud.element.persistence

import dev.getelements.elements.sdk.annotation.ElementServiceExport
import dev.getelements.elements.sdk.annotation.ElementServiceImplementation
import dev.getelements.elements.sdk.dao.EntityRegistry

/**
 * Declares this element's Morphia entity classes to the platform. The platform pre-registers
 * these with the `Mapper` — in the element's classloader — before any REST endpoint is served,
 * which is what lets [CloudConnectStateDocument] be queried without a discriminator.
 *
 * Annotated per the `EntityRegistry` SPI contract: the platform resolves the export through the
 * element's service locator (`locator.findInstance(EntityRegistry.class)`), so the binding must
 * also be exposed from this element's Guice environment — see [com.namazustudios.cloud.element.guice.CloudClientModule].
 */
@ElementServiceImplementation
@ElementServiceExport(EntityRegistry::class)
class CloudConnectEntityRegistry : EntityRegistry {
    override fun entityClasses(): List<Class<*>> = listOf(
        CloudConnectStateDocument::class.java,
    )
}
