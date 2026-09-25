// This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
// If a copy of the MPL was not distributed with this file, You can obtain one at
// https://mozilla.org/MPL/2.0/.

package com.namazustudios.cloud.element.persistence

import com.google.inject.AbstractModule
import com.google.inject.Guice
import com.google.inject.Key
import com.google.inject.Stage
import com.google.inject.name.Names
import com.mongodb.client.MongoClient
import com.mongodb.client.MongoClients
import com.namazustudios.cloud.element.CloudClientAttributes
import com.namazustudios.cloud.element.guice.CloudClientModule
import dev.getelements.elements.sdk.dao.EntityRegistry
import dev.getelements.elements.sdk.dao.SessionDao
import dev.getelements.elements.sdk.dao.UserDao
import dev.morphia.Datastore
import dev.morphia.Morphia
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.lang.reflect.Proxy

/**
 * Retires the one risk the unit tests cannot: that the element's wiring resolves the way the
 * platform actually resolves it.
 *
 *  - [guiceExposure] mirrors `GuiceServiceLocator.findInstance`, which is how the platform's
 *    `MongoElementEntityRegistrar` discovers the [EntityRegistry] export. The registry binding
 *    must be visible outside [CloudClientModule]'s PrivateModule environment or the entity
 *    classes silently go unregistered.
 *
 *  - The round-trip tests mirror `MongoElementEntityRegistrar.applyChanges`, which maps each
 *    class from `registry.entityClasses()` onto a fresh `Datastore` — exercising the real
 *    `@Entity` annotations and the `useDiscriminator = false` declaration against real Morphia.
 *
 * The Morphia tests run against the local MongoDB from `services-dev/docker-compose.yml` and skip
 * (rather than fail) when it is unreachable, so a plain `mvn test` without services still passes.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CloudConnectWiringTest {

    companion object {
        private const val DB_NAME = "namazu_cloud_connect_wiring_test"

        /** Morphia's default collection name for the entity. */
        private const val COLLECTION = "cloud_connect_state"

        private val mongoUri = System.getenv("MONGODB_URI") ?: "mongodb://localhost:27017"

        /**
         * A `Datastore` that must never be called — it exists only so the Guice graph is complete.
         * A JDK proxy throws on any invocation, making accidental use loud rather than subtle.
         */
        private val datastoreStub: Datastore = Proxy.newProxyInstance(
            Datastore::class.java.classLoader,
            arrayOf(Datastore::class.java),
        ) { _, method, _ -> error("stub Datastore must not be used: ${method.name}") } as Datastore

        /** Even in TOOL stage Guice validates dependency keys; the platform supplies these at runtime. */
        private val sessionDaoStub: SessionDao = Proxy.newProxyInstance(
            SessionDao::class.java.classLoader,
            arrayOf(SessionDao::class.java),
        ) { _, method, _ -> error("stub SessionDao must not be used: ${method.name}") } as SessionDao

        private val userDaoStub: UserDao = Proxy.newProxyInstance(
            UserDao::class.java.classLoader,
            arrayOf(UserDao::class.java),
        ) { _, method, _ -> error("stub UserDao must not be used: ${method.name}") } as UserDao

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
    fun `guice exposure - entity registry is visible outside the private module`() {
        val injector = Guice.createInjector(
            Stage.TOOL,
            object : AbstractModule() {
                override fun configure() {
                    bind(Datastore::class.java).toInstance(datastoreStub)
                    bind(SessionDao::class.java).toInstance(sessionDaoStub)
                    bind(UserDao::class.java).toInstance(userDaoStub)
                    // Blank values keep CloudClientService inert (NOT_CONFIGURED) if it were ever
                    // instantiated outside TOOL stage.
                    bind(String::class.java).annotatedWith(Names.named(CloudClientAttributes.URL)).toInstance("")
                    bind(String::class.java).annotatedWith(Names.named(CloudClientAttributes.ID)).toInstance("")
                    bind(String::class.java).annotatedWith(Names.named(CloudClientAttributes.SECRET)).toInstance("")
                    bind(String::class.java).annotatedWith(Names.named(CloudClientAttributes.RETRY_SECONDS)).toInstance("5")
                    bind(String::class.java).annotatedWith(Names.named(CloudClientAttributes.SESSION_TTL_MINUTES)).toInstance("60")
                    install(CloudClientModule())
                }
            },
        )

        // This is the lookup the platform performs; before the expose() fix it returned null.
        val binding = injector.getExistingBinding(Key.get(EntityRegistry::class.java))
        assertNotNull(binding, "EntityRegistry must be resolvable from the element injector")
        assertTrue(binding!!.provider.get() is CloudConnectEntityRegistry)
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
