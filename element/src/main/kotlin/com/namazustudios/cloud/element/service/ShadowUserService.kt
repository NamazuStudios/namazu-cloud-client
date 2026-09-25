// This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
// If a copy of the MPL was not distributed with this file, You can obtain one at
// https://mozilla.org/MPL/2.0/.

package com.namazustudios.cloud.element.service

import com.namazustudios.cloud.protocol.ControlActor
import dev.getelements.elements.sdk.dao.UserDao
import dev.getelements.elements.sdk.model.exception.user.UserNotFoundException
import dev.getelements.elements.sdk.model.user.User
import jakarta.inject.Inject
import jakarta.inject.Singleton
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Ensures a per-actor shadow superuser exists so the control plane can act on this instance as the
 * cloud user that asked for the session. The shadow user name is a deterministic function of the
 * cloud user id (`cloud-` + SHA-256 prefix), so repeated requests for the same actor reuse the same
 * account instead of accumulating duplicates.
 *
 * The shadow user is always [User.Level.SUPERUSER] — the session it mints is authenticated against
 * this instance's own REST API, and is only ever handed out after the mutual HMAC handshake above.
 * `actor.userEmail` is deliberately NOT used as the local account's email: a Namazu Cloud user's
 * email may already exist (or arrive later) as a real local account, and shadowing it would confuse
 * login.
 */
@Singleton
class ShadowUserService @Inject constructor(
    private val userDao: UserDao,
) {

    /** Returns the id of the shadow superuser for [actor], creating it if necessary. */
    fun ensureShadowUser(actor: ControlActor): User {
        val name = shadowName(actor.userId)
        val existing = runCatching { userDao.findUserByNameOrEmail(name).orElse(null) }
            .recoverCatching { e: Throwable ->
                if (e is UserNotFoundException) null else throw e
            }
            .getOrNull()
        if (existing != null) {
            if (existing.level != User.Level.SUPERUSER) {
                // We created it, so this can only happen if an operator hand-edited the account.
                throw IllegalStateException("shadow user '$name' exists but is ${existing.level}, refusing to use it")
            }
            return existing
        }
        val password = randomPassword()
        val user = User().apply {
            this.name = name
            this.email = "$name@local.namazu.cloud"
            this.level = User.Level.SUPERUSER
        }
        return userDao.createUserWithPasswordStrict(user, password)
    }

    companion object {
        private const val NAME_PREFIX = "cloud"
        private const val NAME_HASH_LEN = 32
        private val RANDOM = SecureRandom()

        /** The deterministic shadow account name for a cloud user id. */
        fun shadowName(cloudUserId: String): String {
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(cloudUserId.toByteArray(Charsets.UTF_8))
            val hex = digest.joinToString("") { b -> "%02x".format(b) }
            return "$NAME_PREFIX-${hex.take(NAME_HASH_LEN)}"
        }

        private fun randomPassword(): String {
            val bytes = ByteArray(48)
            RANDOM.nextBytes(bytes)
            return bytes.joinToString("") { b -> "%02x".format(b) }
        }
    }
}