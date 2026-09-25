// This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
// If a copy of the MPL was not distributed with this file, You can obtain one at
// https://mozilla.org/MPL/2.0/.

package com.namazustudios.cloud.element

import com.namazustudios.cloud.element.rest.CloudConnectExceptionMapper
import com.namazustudios.cloud.element.rest.CloudConnectObjectMapperProvider
import com.namazustudios.cloud.element.rest.CloudConnectResource
import dev.getelements.elements.sdk.annotation.ElementDefaultAttribute
import dev.getelements.elements.sdk.annotation.ElementServiceExport
import dev.getelements.elements.sdk.annotation.ElementServiceImplementation
import jakarta.ws.rs.core.Application

/**
 * Registers the Namazu Cloud Connect REST surface (the superuser dashboard page's backing API)
 * with the platform's JAX-RS runtime.
 *
 * `expose = false` keeps the application out of the platform's shared parent injector, which is
 * the current SDK archetype's recommendation for new elements.
 *
 * The [RS_ROOT] is `/cloud-connect/api` rather than `/cloud/api` on purpose: this connector ships
 * alongside the Namazu Cloud control-plane element in the same deployment, and the control plane
 * already owns `/cloud/api`. Two elements claiming the same RS root would collide.
 */
@ElementServiceImplementation
@ElementServiceExport(value = [Application::class], expose = false)
class CloudClientApplication : Application() {

    companion object {

        /**
         * Turns on the platform's authentication filter, which resolves the
         * `Elements-SessionSecret` header into a `UserService.currentUser` and rejects anonymous
         * calls before they reach [CloudConnectResource].
         */
        @JvmField
        @ElementDefaultAttribute(value = "true")
        val AUTH_ENABLED: String = "dev.getelements.elements.auth.enabled"

        @JvmField
        @ElementDefaultAttribute(value = "/cloud-connect/api")
        val RS_ROOT: String = "dev.getelements.elements.element.rs.root"

        const val OPENAPI_TAG: String = "NamazuCloudConnect"
    }

    override fun getClasses(): Set<Class<*>> = setOf(
        // Endpoints
        CloudConnectResource::class.java,
        // Providers
        CloudConnectExceptionMapper::class.java,
    )

    // The Jackson provider is registered as a singleton rather than a class: it is
    // stateful (it wraps a configured ObjectMapper), and registering it in both sets would
    // give the runtime two instances to choose between.
    override fun getSingletons(): Set<Any> = setOf(
        CloudConnectObjectMapperProvider(),
    )
}
