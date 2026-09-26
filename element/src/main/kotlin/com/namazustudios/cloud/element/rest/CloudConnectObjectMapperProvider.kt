// This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
// If a copy of the MPL was not distributed with this file, You can obtain one at
// https://mozilla.org/MPL/2.0/.

package com.namazustudios.cloud.element.rest

import com.fasterxml.jackson.annotation.JsonSetter
import com.fasterxml.jackson.annotation.Nulls
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.jakarta.rs.json.JacksonJsonProvider
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.ext.Provider
import java.util.List
import java.util.Map

/**
 * Jackson provider for the Namazu Cloud Connect REST surface.
 *
 * Kotlin data classes expose non-nullable constructor properties with no JavaBean setters, which
 * Jackson cannot populate on its own; the `Nulls.AS_EMPTY` setter override for [List]/[Map] makes
 * omitted JSON members fall back to those defaults instead of failing. Every response type on
 * this surface is a data class of primitives, so no per-type mixins are needed.
 */
@Provider
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
class CloudConnectObjectMapperProvider : JacksonJsonProvider(MAPPER) {

    companion object {
        val MAPPER: ObjectMapper = ObjectMapper()
            .also {
                it.configOverride(List::class.java)
                    .setSetterInfo(JsonSetter.Value.forValueNulls(Nulls.AS_EMPTY))
                it.configOverride(Map::class.java)
                    .setSetterInfo(JsonSetter.Value.forValueNulls(Nulls.AS_EMPTY))
            }
    }
}
