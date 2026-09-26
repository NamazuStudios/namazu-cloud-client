// This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
// If a copy of the MPL was not distributed with this file, You can obtain one at
// https://mozilla.org/MPL/2.0/.

package com.namazustudios.cloud.element.rest

import jakarta.ws.rs.WebApplicationException
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import jakarta.ws.rs.ext.ExceptionMapper
import jakarta.ws.rs.ext.Provider
import org.slf4j.LoggerFactory

/**
 * Turns any uncaught exception into a JSON body so the dashboard can render a message instead of
 * receiving an HTML error page. `WebApplicationException`s pass through with their own status,
 * which is how the superuser gates on [CloudConnectResource] return 401/403.
 */
@Provider
class CloudConnectExceptionMapper : ExceptionMapper<Exception> {

    private val log = LoggerFactory.getLogger(CloudConnectExceptionMapper::class.java)

    override fun toResponse(exception: Exception): Response {
        if (exception is WebApplicationException) {
            return exception.response
        }
        log.error("Unhandled exception on the Namazu Cloud Connect REST surface", exception)
        return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
            .type(MediaType.APPLICATION_JSON_TYPE)
            .entity(mapOf("message" to (exception.message ?: "An unexpected error occurred.")))
            .build()
    }
}
