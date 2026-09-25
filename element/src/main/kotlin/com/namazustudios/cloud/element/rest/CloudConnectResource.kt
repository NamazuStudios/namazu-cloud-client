// This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
// If a copy of the MPL was not distributed with this file, You can obtain one at
// https://mozilla.org/MPL/2.0/.

package com.namazustudios.cloud.element.rest

import com.namazustudios.cloud.config.CloudClientConfig
import com.namazustudios.cloud.element.CloudClientApplication
import com.namazustudios.cloud.element.service.CloudClientService
import dev.getelements.elements.sdk.model.Headers.SESSION_SECRET
import dev.getelements.elements.sdk.model.user.User
import dev.getelements.elements.sdk.service.user.UserService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.inject.Inject
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.ForbiddenException
import jakarta.ws.rs.GET
import jakarta.ws.rs.NotAuthorizedException
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType

/**
 * The REST surface behind the "Namazu Cloud Connect" superuser dashboard page: a read-only
 * parameter readout, a masked status payload with a live green/red light, and an enable/disable
 * toggle that connects or disconnects the control channel.
 *
 * Every endpoint is superuser-only. The page lives in the `superuser/` segment and the dashboard
 * sends an `Elements-SessionSecret`, but the check is repeated here rather than trusted to the
 * route — this endpoint can hand out the shared secret and switch the remote-control channel on,
 * so it must be self-protecting. The check is inline (not a service-provider split) because the
 * surface is small and always superuser-scoped; it also means a future routing change cannot
 * silently expose it.
 */
@Tag(name = CloudClientApplication.OPENAPI_TAG)
@Path("/connect")
class CloudConnectResource {

    @Inject
    lateinit var cloudClientService: CloudClientService

    @Inject
    lateinit var userService: UserService

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(
        summary = "Namazu Cloud Connect status and parameter readout",
        security = [SecurityRequirement(name = SESSION_SECRET)],
    )
    fun getStatus(): ConnectStatusDto {
        requireSuperuser()
        return buildStatus()
    }

    /**
     * Connects or disconnects the control channel and persists the operator's preference, so the
     * choice survives a restart. Idempotent: setting the flag it already holds is a no-op beyond
     * re-persisting it.
     */
    @POST
    @Path("/enabled")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(
        summary = "Enable or disable the Namazu Cloud control channel",
        security = [SecurityRequirement(name = SESSION_SECRET)],
    )
    fun setEnabled(request: SetEnabledRequest): ConnectStatusDto {
        requireSuperuser()
        cloudClientService.setEnabled(request.enabled)
        return buildStatus()
    }

    /**
     * Returns the shared secret in plaintext. Deliberately a separate endpoint from
     * `GET /connect` so that the frequently-polled status payload never carries it; the dashboard
     * only calls this when a superuser clicks Reveal.
     */
    @GET
    @Path("/secret")
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(
        summary = "Reveal the shared client secret (superuser only)",
        security = [SecurityRequirement(name = SESSION_SECRET)],
    )
    fun revealSecret(): RevealSecretDto {
        requireSuperuser()
        val secret = cloudClientService.revealSecret()
            ?: throw NotAuthorizedException("No shared secret is configured on this instance.")
        return RevealSecretDto(secret)
    }

    private fun buildStatus(): ConnectStatusDto {
        val config = cloudClientService.resolvedConfig()
        val persisted = cloudClientService.persistedState()
        val state = cloudClientService.connectionState()

        val parameters = ConnectParametersDto(
            url = config?.url?.value.orEmpty(),
            controlUri = config?.controlUri().orEmpty(),
            clientId = config?.id?.value.orEmpty(),
            secret = MASK,
            secretConfigured = cloudClientService.revealSecret() != null,
            retrySeconds = config?.retrySeconds ?: CloudClientConfig.RETRY_SECONDS_DEFAULT,
            sessionTtlMinutes = config?.sessionTtlMinutes
                ?: CloudClientConfig.SESSION_TTL_MINUTES_DEFAULT,
            values = listOf(
                parameter(CloudClientConfig.URL, config?.url),
                parameter(CloudClientConfig.ID, config?.id),
                parameter(CloudClientConfig.SECRET, config?.secret, masked = true),
                numberParameter(
                    CloudClientConfig.RETRY_SECONDS,
                    config?.retrySeconds ?: CloudClientConfig.RETRY_SECONDS_DEFAULT,
                ),
                numberParameter(
                    CloudClientConfig.SESSION_TTL_MINUTES,
                    config?.sessionTtlMinutes ?: CloudClientConfig.SESSION_TTL_MINUTES_DEFAULT,
                ),
            ),
        )

        return ConnectStatusDto(
            enabled = cloudClientService.isEnabled(),
            state = state.wireName,
            connected = cloudClientService.isConnected(),
            detail = state.detail(),
            lastConnectedAt = cloudClientService.lastConnectedAt(),
            lastDisconnectedAt = cloudClientService.lastDisconnectedAt(),
            lastError = cloudClientService.lastError(),
            updatedAt = persisted?.updatedAt,
            parameters = parameters,
        )
    }

    private fun parameter(
        key: String,
        resolved: CloudClientService.ResolvedValue?,
        masked: Boolean = false,
    ): ParameterValueDto {
        val envKey = CloudClientConfig.envKeyFor(key)
        return ParameterValueDto(
            key = key,
            value = when {
                resolved == null -> ""
                masked -> MASK
                else -> resolved.value
            },
            source = resolved?.source?.name ?: CloudClientService.ValueSource.ATTRIBUTE.name,
            envKey = envKey,
            overriddenByEnvironment = System.getenv(envKey)?.isNotBlank() == true,
        )
    }

    private fun numberParameter(key: String, value: Long): ParameterValueDto {
        val envKey = CloudClientConfig.envKeyFor(key)
        return ParameterValueDto(
            key = key,
            value = value.toString(),
            source = CloudClientService.ValueSource.ATTRIBUTE.name,
            envKey = envKey,
            overriddenByEnvironment = System.getenv(envKey)?.isNotBlank() == true,
        )
    }

    private fun requireSuperuser() {
        val user = userService.currentUser ?: throw NotAuthorizedException("Authentication is required.")
        if (user.level != User.Level.SUPERUSER) {
            throw ForbiddenException("Only superusers can manage the Namazu Cloud connection.")
        }
    }

    companion object {
        /** Fixed-width mask standing in for the secret until a superuser explicitly reveals it. */
        const val MASK = "••••••••••••"
    }
}
