// This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
// If a copy of the MPL was not distributed with this file, You can obtain one at
// https://mozilla.org/MPL/2.0/.

package com.namazustudios.cloud.element.service

import com.google.inject.name.Named
import com.namazustudios.cloud.config.CloudClientConfig
import com.namazustudios.cloud.element.CloudClientAttributes
import com.namazustudios.cloud.protocol.ControlActor
import com.namazustudios.cloud.protocol.ControlSession
import dev.getelements.elements.sdk.dao.SessionDao
import dev.getelements.elements.sdk.model.session.Session
import jakarta.inject.Inject
import jakarta.inject.Singleton
import org.slf4j.LoggerFactory

/**
 * Mints an `Elements-SessionSecret` bound to the shadow superuser for a cloud actor. This is the
 * remote-control primitive: once handed to the cloud, the secret is fed to the platform REST API as
 * the `Elements-SessionSecret` header, and the session's target user is the shadow superuser.
 */
@Singleton
class ControlSessionMinter @Inject constructor(
    private val sessionDao: SessionDao,
    private val shadowUserService: ShadowUserService,
    @Named(CloudClientAttributes.SESSION_TTL_MINUTES) private val ttlMinutes: String,
) {
    private val log = LoggerFactory.getLogger(ControlSessionMinter::class.java)

    /**
     * Creates a fresh session, honoring [expiresAt] from the cloud when given (epoch millis),
     * otherwise falling back to the configured default TTL.
     *
     * @throws Exception on any failure (caller turns it into a `session_mint_failed` error frame).
     */
    fun mint(actor: ControlActor, expiresAt: Long?): ControlSession {
        val shadow = shadowUserService.ensureShadowUser(actor)
        val expiry = expiresAt ?: (System.currentTimeMillis() + ttlMinutes.toLong() * 60_000L)
        val session = Session().also {
            it.user = shadow
            it.expiry = expiry
        }
        val creation = sessionDao.create(session)
        log.info(
            "minting control session for cloud actor user={} level={} as shadow superuser id={} name={}",
            actor.userId, actor.level, shadow.id, shadow.name,
        )
        return ControlSession(
            requestId = "",
            sessionSecret = creation.sessionSecret,
            expiresAt = expiry,
            userId = shadow.id,
            userName = shadow.name,
        )
    }
}