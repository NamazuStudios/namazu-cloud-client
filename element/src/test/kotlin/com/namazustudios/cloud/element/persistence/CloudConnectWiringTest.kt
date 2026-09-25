// This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
// If a copy of the MPL was not distributed with this file, You can obtain one at
// https://mozilla.org/MPL/2.0/.

package com.namazustudios.cloud.element.persistence

import com.mongodb.client.MongoClient
import com.mongodb.client.MongoClients
import com.namazustudios.cloud.element.service.CloudClientService
import dev.getelements.elements.sdk.annotation.ElementServiceImplementation
import dev.morphia.Datastore
import dev.morphia.Morphia
import dev.getelements.elements.sdk.dao.EntityRegistry
import dev.getelements.elements.sdk.record.ElementServiceRecord
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

/**
 * Retires the risks the unit tests cannot otherwise see, by mirroring exactly what the platform
 * does at element load.
 *
 *  - [entityRegistryExport] mirrors the discovery step: the loader scans
 *    `@ElementServiceExport` via `ElementServiceRecord.fromClass` (the same record
 *    `GuiceSpiModule.bindAndExposeService` consumes). It also guards the inverse failure mode: an
 *    additional manual `bind`/`expose` of `EntityRegistry` in [com.namazustudios.cloud.element.guice.CloudClientModule]
 *    would collide with the loader's own binding and fail the element with
 *    `Guice/BindingAlreadySet` — which the loader swallows as "Caught exception loading element.
 *    Skipping.", so the element would silently not start.
 *
 *  - The round-trip tests mirror `MongoElementEntityRegistrar.applyChanges`, which maps each
 *    class from `registry.entityClasses()` onto a fresh `Datastore` — exercising the real
 *    `@Entity` annotations and the `useDiscriminator = false` declaration against real Morphia.
 *
 * The Morphia tests run against the local MongoDB from `services-dev/docker-compose.yml` and skip
 * (rather than fail) when it is unreachable, so a plain `mvn test` without services still passes.
 * The full element boot is additionally verified by the `debug` module's loopback harness.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CloudConnectWiringTest {

    companion object {
        private const val DB_NAME = "namazu_cloud_connect_wiring_test"

        /** Morphia's default collection name for the entity. */
        private const val COLLECTION = "cloud_connect_state"

        private val mongoUri = System.getenv("MONGODB_URI") ?: "mongodb://localhost:27017"

        private var client: MongoClient? = null
    }

    private lateinit var datastore: Datastore

    @BeforeAll
    fun requireMongo() {
        assumeTrue(mongoIsReachable(), "MongoDB at $mongoUri is not reachable; skipping Morphia round-trip")
        client = MongoClients.create(mongoUri)
    }

    @AfterAll
    fun closeClient() {
        runCatching { client?.getDatabase(DB_NAME)?.drop() }
        runCatching { client?.close() }
        client = null
    }

    @BeforeEach
    fun createDatastore() {
        datastore = Morphia.createDatastore(client!!, DB_NAME)
        // The exact call the platform's MongoElementEntityRegistrar.applyChanges makes for every
        // class in CloudConnectEntityRegistry.entityClasses().
        datastore.mapper.map(CloudConnectStateDocument::class.java)
        datastore.database.drop()
    }

    @Test
    fun `entity registry is exported by annotation, exactly once`() {
        val records = ElementServiceRecord.fromClass(CloudConnectEntityRegistry::class.java).toList()

        assertEquals(1, records.size, "CloudConnectEntityRegistry must carry exactly one @ElementServiceExport")

        val record = records[0]
        assertTrue(
            record.export().exposed().contains(EntityRegistry::class.java),
            "The export must expose EntityRegistry",
        )
        assertEquals(
            CloudConnectEntityRegistry::class.java,
            record.implementation().type(),
            "The loader must bind EntityRegistry to CloudConnectEntityRegistry",
        )
    }

    @Test
    fun `cloud client service is exported for the Jersey bridge`() {
        // ElementBinder (the Jersey/HK2 bridge) only injects element services whose export carries
        // expose=true; without the export, CloudConnectResource cannot receive the service and every
        // REST request fails with a 500 UnsatisfiedDependencyException.
        val records = ElementServiceRecord.fromClass(CloudClientService::class.java).toList()

        assertEquals(1, records.size, "CloudClientService must carry exactly one @ElementServiceExport")
        assertTrue(records[0].export().expose(), "The export must be visible to the Jersey bridge")
        // A plain @ElementServiceExport (no @ElementServiceImplementation) must report the
        // DefaultImplementation marker: that is the loader's signal to defer binding to
        // CloudClientModule rather than bind the type itself (which would collide with the
        // module's eager binding and fail the element with Guice/BindingAlreadySet).
        assertEquals(
            ElementServiceImplementation.DefaultImplementation::class.java,
            records[0].implementation().type(),
            "The export must defer binding to CloudClientModule",
        )
    }

    @Test
    fun `entity registry declares the state document`() {
        assertEquals(
            listOf(CloudConnectStateDocument::class.java),
            CloudConnectEntityRegistry().entityClasses(),
        )
    }

    @Test
    fun `load on a fresh database yields null and the default enabled flag`() {
        val dao = CloudConnectStateDao(datastore)
        assertEquals(null, dao.load())
        assertEquals(CloudConnectStateDao.DEFAULT_ENABLED, dao.loadEnabled())
    }

    @Test
    fun `update persists and reloads the singleton document`() {
        val dao = CloudConnectStateDao(datastore)

        val saved = dao.update {
            it.enabled = false
            it.state = "DISABLED"
            it.lastError = "test write"
        }
        assertNotNull(saved, "update should succeed against a reachable datastore")

        assertEquals(false, dao.loadEnabled())
        val reloaded = dao.load()
        assertNotNull(reloaded)
        assertEquals("DISABLED", reloaded!!.state)
        assertEquals("test write", reloaded.lastError)
        assertTrue(reloaded.updatedAt > 0)
    }

    @Test
    fun `repeated updates keep exactly one document`() {
        val dao = CloudConnectStateDao(datastore)
        repeat(3) { dao.update { it.state = "CONNECTING" } }
        assertEquals(1, datastore.database.getCollection(COLLECTION).countDocuments())
    }

    private fun mongoIsReachable(): Boolean = runCatching {
        MongoClients.create("$mongoUri/?serverSelectionTimeoutMS=2000").use { probe ->
            probe.listDatabaseNames().firstOrNull() != null
        }
    }.getOrDefault(false)

}
