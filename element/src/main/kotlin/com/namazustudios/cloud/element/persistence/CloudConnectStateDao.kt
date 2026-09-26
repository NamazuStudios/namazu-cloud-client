// This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
// If a copy of the MPL was not distributed with this file, You can obtain one at
// https://mozilla.org/MPL/2.0/.

package com.namazustudios.cloud.element.persistence

import dev.morphia.Datastore
import dev.morphia.query.filters.Filters
import org.slf4j.LoggerFactory
import jakarta.inject.Inject
import jakarta.inject.Singleton

/**
 * Read/write access to the singleton [CloudConnectStateDocument].
 *
 * The injected [Datastore] is the platform's own Morphia datastore (with the SDK's codecs already
 * registered) — it must not be constructed by hand via `MongoClients.create`, which would lose
 * those codecs. The singleton `id` is unique per deployment, so a plain find/save is sufficient and
 * no [dev.getelements.elements.sdk.dao.Transaction] is needed for these single-document writes.
 *
 * Every method is failure-tolerant: the connector's control-channel lifecycle must never be taken
 * down by a database outage, so a read or write that throws is logged and the caller falls back to
 * its in-memory value.
 */
@Singleton
class CloudConnectStateDao @Inject constructor(
    private val datastore: Datastore,
) {

    private val log = LoggerFactory.getLogger(CloudConnectStateDao::class.java)

    /**
     * Reads the persisted state, or `null` when absent (first boot) or unreadable. Callers treat
     * `null` as "use defaults" — notably [DEFAULT_ENABLED] so the connector is on until a
     * superuser turns it off.
     */
    fun load(): CloudConnectStateDocument? = runCatching {
        datastore.find(CloudConnectStateDocument::class.java)
            .filter(Filters.eq("_id", CloudConnectStateDocument.DOC_ID))
            .first()
    }.onFailure {
        log.warn("could not read persisted cloud-connect state; falling back to defaults", it)
    }.getOrNull()

    /** Reads just the operator's enable flag, defaulting to `true` when absent or unreadable. */
    fun loadEnabled(): Boolean = load()?.enabled ?: DEFAULT_ENABLED

    /**
     * Applies [mutate] to the singleton document and saves it, stamping [CloudConnectStateDocument.updatedAt].
     * Returns the saved document, or `null` if the write failed.
     */
    fun update(mutate: (CloudConnectStateDocument) -> Unit): CloudConnectStateDocument? = runCatching {
        val doc = load() ?: CloudConnectStateDocument()
        mutate(doc)
        doc.updatedAt = System.currentTimeMillis()
        datastore.save(doc)
        doc
    }.onFailure {
        log.warn("could not persist cloud-connect state", it)
    }.getOrNull()

    companion object {
        /**
         * The enable flag when no document exists yet. `true` preserves the connector's original
         * always-on behaviour — customers opt OUT of the cloud connection, they do not opt in.
         */
        const val DEFAULT_ENABLED = true
    }
}
