// This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
// If a copy of the MPL was not distributed with this file, You can obtain one at
// https://mozilla.org/MPL/2.0/.

package com.namazustudios.cloud.element.service

import com.google.inject.name.Named
import com.namazustudios.cloud.config.CloudClientConfig
import com.namazustudios.cloud.element.CloudClientAttributes
import com.namazustudios.cloud.element.channel.ControlChannel
import com.namazustudios.cloud.element.persistence.CloudConnectStateDao
import com.namazustudios.cloud.element.persistence.CloudConnectStateDocument
import com.namazustudios.cloud.element.transport.WebSocketTransportPicker
import com.namazustudios.cloud.protocol.ControlMac
import dev.getelements.elements.sdk.annotation.ElementServiceExport
import jakarta.inject.Inject
import jakarta.inject.Singleton
import org.slf4j.LoggerFactory
import java.net.URI
import java.util.concurrent.TimeUnit

/**
 * Owns the control channel lifecycle. Binds eagerly (via [CloudClientModule]) so the connector
 * starts dialing out as soon as the element loads.
 *
 * [ElementServiceExport] is required for the REST surface: the platform's Jersey bridge
 * (`ElementBinder`) builds the resource's HK2 locator from the element record's exported services,
 * so without the export `CloudConnectResource` cannot receive this instance and every request
 * fails with an UnsatisfiedDependencyException (surfacing as HTTP 500). The binding itself is
 * supplied by [CloudClientModule] — the export annotation only declares and exposes it.
 *
 * Two switches govern whether the connector dials out, and they are independent:
 *
 *  1. **Configuration** — the client id and shared secret must both resolve to non-blank values.
 *     If either is blank the channel idles in [CloudConnectState.NOT_CONFIGURED] and logs a
 *     warning; no connection is attempted.
 *  2. **Operator intent** — the persisted enable flag ([CloudConnectStateDao]). It defaults to
 *     `true`, so a fresh deployment connects as it always has; a superuser switching the connector
 *     off in the dashboard persists `false` and the connector stays down across restarts.
 *
 * Config precedence per key: `System.getenv(<envKey>)` wins over the element attribute; the
 * attribute defaults are declared in [CloudClientAttributes]. This matches the proxy convention
 * that Conductor-injected task environments carry `com_namazustudios_cloud_client_*` values.
 *
 * A single daemon worker thread owns the dial/handshake/serve/retry cycle. [setEnabled] starts and
 * stops that worker; the retry delay is a wait on [lifecycleLock] rather than a bare sleep, so a
 * disable takes effect immediately instead of after the remainder of the backoff.
 */
@ElementServiceExport(CloudClientService::class)
@Singleton
class CloudClientService @Inject constructor(
    private val dispatcher: ControlCommandDispatcher,
    private val stateDao: CloudConnectStateDao,
) {
    private val log = LoggerFactory.getLogger(CloudClientService::class.java)

    /** Guards [enabled] and the retry wait, so a disable can interrupt a pending backoff. */
    private val lifecycleLock = Object()

    @Volatile private var enabled: Boolean = false
    @Volatile private var state: CloudConnectState = CloudConnectState.NOT_CONFIGURED
    @Volatile private var lastError: String? = null
    @Volatile private var lastConnectedAt: Long? = null
    @Volatile private var lastDisconnectedAt: Long? = null
    @Volatile private var worker: Thread? = null
    @Volatile private var currentChannel: ControlChannel? = null

    @Volatile private var config: ResolvedConfig? = null
    @Volatile private var persistedEnabled: Boolean? = null

    /**
     * Guice method-injection entry point: resolves configuration, loads the persisted enable flag,
     * and launches the reconnect loop if the connector is meant to be on. Called once at element
     * load.
     */
    @Inject
    fun start(
        @Named(CloudClientAttributes.URL) urlAttr: String,
        @Named(CloudClientAttributes.ID) idAttr: String,
        @Named(CloudClientAttributes.SECRET) secretAttr: String,
        @Named(CloudClientAttributes.RETRY_SECONDS) retryAttr: String,
        @Named(CloudClientAttributes.SESSION_TTL_MINUTES) sessionTtlAttr: String,
    ) {
        val resolved = ResolvedConfig(
            url = resolve(CloudClientConfig.URL, urlAttr),
            id = resolve(CloudClientConfig.ID, idAttr),
            secret = resolve(CloudClientConfig.SECRET, secretAttr),
            retrySeconds = resolve(CloudClientConfig.RETRY_SECONDS, retryAttr)
                .value.toLongOrNull() ?: CloudClientConfig.RETRY_SECONDS_DEFAULT,
            sessionTtlMinutes = resolve(CloudClientConfig.SESSION_TTL_MINUTES, sessionTtlAttr)
                .value.toLongOrNull() ?: CloudClientConfig.SESSION_TTL_MINUTES_DEFAULT,
        )
        config = resolved

        val enabledOnBoot = stateDao.loadEnabled()
        persistedEnabled = enabledOnBoot

        if (!enabledOnBoot) {
            log.info("Namazu Cloud Connect is disabled by operator preference; no connection will be attempted")
            transitionTo(CloudConnectState.DISABLED, error = null, persistIntent = true)
            return
        }
        startConnectorIfConfigured(resolved, enabledOnBoot)
    }

    // ---------------------------------------------------------------- operator control

    /**
     * Switches the connector on or off, persisting the choice so it survives a restart, and
     * starting or stopping the dial loop immediately.
     *
     * @return the state the connector ended up in.
     */
    fun setEnabled(value: Boolean): CloudConnectState {
        synchronized(lifecycleLock) {
            enabled = value
            lifecycleLock.notifyAll()
        }
        persistedEnabled = value
        stateDao.update { it.enabled = value }

        val resolved = config
        if (resolved == null) {
            // start() has not run yet; the flag is persisted and start() will honour it.
            return state
        }
        if (value) {
            log.info("Namazu Cloud Connect enabled by operator; starting control channel")
            startConnectorIfConfigured(resolved, value)
        } else {
            log.info("Namazu Cloud Connect disabled by operator; closing control channel")
            stopConnector()
            transitionTo(CloudConnectState.DISABLED, error = null, persistIntent = false)
        }
        return state
    }

    /** The operator's on/off preference, as persisted. */
    fun isEnabled(): Boolean = persistedEnabled ?: enabled

    /** The current live state of the control channel. */
    fun connectionState(): CloudConnectState = state

    /** True only while the mutual handshake has completed and the channel is serving. */
    fun isConnected(): Boolean = state == CloudConnectState.CONNECTED

    /** Message from the last failed attempt, or `null`. Never contains the shared secret. */
    fun lastError(): String? = lastError

    fun lastConnectedAt(): Long? = lastConnectedAt

    fun lastDisconnectedAt(): Long? = lastDisconnectedAt

    /** The resolved configuration, or `null` before [start] has run. */
    fun resolvedConfig(): ResolvedConfig? = config

    /**
     * The persisted state document, for the dashboard's timestamps. The DAO itself is deliberately
     * not exposed to the REST layer — it is an internal of this service, and the platform's Jersey
     * bridge would need an explicit `@ElementServiceExport` on it to inject it into resources.
     */
    fun persistedState(): CloudConnectStateDocument? = stateDao.load()

    /**
     * The shared secret in plaintext, for the superuser-gated reveal endpoint only. Callers are
     * responsible for the authorization check; there is no logging of this value anywhere.
     */
    fun revealSecret(): String? = config?.secret?.value?.takeIf { it.isNotBlank() }

    // ---------------------------------------------------------------- lifecycle internals

    private fun startConnectorIfConfigured(resolved: ResolvedConfig, operatorEnabled: Boolean) {
        if (!operatorEnabled) {
            transitionTo(CloudConnectState.DISABLED, error = null, persistIntent = false)
            return
        }
        val missing = listOfNotNull(
            CloudClientConfig.ID.takeIf { resolved.id.value.isBlank() },
            CloudClientConfig.SECRET.takeIf { resolved.secret.value.isBlank() },
        )
        if (missing.isNotEmpty()) {
            log.warn(
                "Namazu Cloud Connect not configured ({}); the control channel is DISABLED until those are set. " +
                    "Set the attributes or their {} env overrides.",
                missing.joinToString(" and ") { "$it=MISSING" },
                CloudClientConfig.ID_ENV.substringBeforeLast('_') + "_*",
            )
            transitionTo(CloudConnectState.NOT_CONFIGURED, error = null, persistIntent = false)
            return
        }

        synchronized(lifecycleLock) {
            if (worker?.isAlive == true) return
            enabled = true
            worker = Thread({ runLoop(resolved) }, "namazu-cloud-control").also {
                it.isDaemon = true
                it.start()
            }
        }
    }

    private fun stopConnector() {
        val thread = synchronized(lifecycleLock) {
            val w = worker
            worker = null
            enabled = false
            lifecycleLock.notifyAll()
            w
        }
        // Close the socket so a worker parked in open()/awaitTerminal() unblocks immediately.
        currentChannel?.let { channel -> runCatching { channel.close() } }
        if (thread != null && thread.isAlive) {
            runCatching { thread.join(STOP_JOIN_TIMEOUT_MILLIS) }
            if (thread.isAlive) log.warn("control-channel worker did not stop within {}ms", STOP_JOIN_TIMEOUT_MILLIS)
        }
        currentChannel = null
        dispatcher.channel = null
    }

    private fun runLoop(resolved: ResolvedConfig) {
        val key = ControlMac.deriveKey(resolved.secret.value)
        val url = URI.create(resolved.controlUri())
        val transport = WebSocketTransportPicker { message, error ->
            if (error == null) log.info(message) else log.warn(message, error)
        }

        log.info(
            "Namazu Cloud Connect dialing {} as clientId {} (transport auto, enabled={})",
            url, resolved.id.value, enabled,
        )

        while (isLoopRunning()) {
            val channel = ControlChannel(
                clientId = resolved.id.value,
                key = key,
                url = url,
                transport = transport,
                log = { message, error ->
                    if (error == null) log.info(message) else log.warn(message, error)
                },
                frameHandler = { frame -> dispatcher(frame) },
                // Bind the dispatcher on the socket thread the moment authentication completes, so a
                // request_session frame that immediately follows the server-auth frame is always
                // answered (no cross-thread race with the run-loop thread).
                onAuthenticated = { channel -> dispatcher.channel = channel },
            )
            currentChannel = channel
            transitionTo(CloudConnectState.CONNECTING, error = null, persistIntent = false)
            try {
                channel.open().get(HANDSHAKE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                log.info("control channel authenticated with Namazu Cloud")
                transitionTo(CloudConnectState.CONNECTED, error = null, persistIntent = false)
                channel.awaitTerminal().get()
                log.info("control channel ran to completion")
            } catch (e: Exception) {
                log.warn("control channel unavailable: {}", e.message ?: e.javaClass.simpleName)
                if (isLoopRunning()) {
                    transitionTo(
                        CloudConnectState.DISCONNECTED,
                        error = e.message ?: e.javaClass.simpleName,
                        persistIntent = false,
                    )
                }
            } finally {
                dispatcher.channel = null
                if (currentChannel === channel) currentChannel = null
                runCatching { channel.abort() }
            }
            if (!isLoopRunning()) break
            log.info("retrying control channel in {}s", resolved.retrySeconds)
            if (!awaitRetryDelay(resolved.retrySeconds)) break
        }
        log.info("Namazu Cloud Connect control-channel worker exiting")
    }

    private fun isLoopRunning(): Boolean = synchronized(lifecycleLock) { enabled }

    /**
     * Sleeps for the retry delay, returning `false` if the connector was disabled (or interrupted)
     * while waiting. A wait on [lifecycleLock] rather than [Thread.sleep] so [setEnabled] can cut
     * the backoff short.
     */
    private fun awaitRetryDelay(seconds: Long): Boolean = synchronized(lifecycleLock) {
        if (!enabled) return false
        try {
            lifecycleLock.wait(seconds * 1000L)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            return false
        }
        enabled
    }

    /**
     * Records a state transition and, unless told otherwise, writes the observed state to the
     * database so the dashboard and operators can see it without a live element.
     *
     * @param persistIntent when true the operator's enable flag is written too, which is how the
     *   boot path and the disable path keep the persisted record in step with reality.
     */
    private fun transitionTo(
        next: CloudConnectState,
        error: String?,
        persistIntent: Boolean,
    ) {
        val wasConnected = state == CloudConnectState.CONNECTED
        state = next
        lastError = error
        val now = System.currentTimeMillis()

        if (next == CloudConnectState.CONNECTED) {
            lastConnectedAt = now
            lastDisconnectedAt = null
        } else if (wasConnected) {
            lastDisconnectedAt = now
        }

        val snapshotEnabled = persistedEnabled
        stateDao.update { doc ->
            doc.connected = next == CloudConnectState.CONNECTED
            doc.state = next.wireName
            doc.lastConnectedAt = lastConnectedAt
            doc.lastDisconnectedAt = lastDisconnectedAt
            doc.lastError = error
            if (persistIntent && snapshotEnabled != null) doc.enabled = snapshotEnabled
        }
    }

    // ---------------------------------------------------------------- config resolution

    private fun resolve(attributeKey: String, attributeValue: String): ResolvedValue {
        val fromEnv = System.getenv(CloudClientConfig.envKeyFor(attributeKey))
            ?.takeIf { it.isNotBlank() }
        return if (fromEnv != null) {
            ResolvedValue(fromEnv, source = ValueSource.ENVIRONMENT)
        } else {
            ResolvedValue(attributeValue, source = ValueSource.ATTRIBUTE)
        }
    }

    // ---------------------------------------------------------------- snapshot types

    /** Where a configuration value came from, so the dashboard can show which layer won. */
    enum class ValueSource { ENVIRONMENT, ATTRIBUTE }

    data class ResolvedValue(val value: String, val source: ValueSource)

    data class ResolvedConfig(
        val url: ResolvedValue,
        val id: ResolvedValue,
        val secret: ResolvedValue,
        val retrySeconds: Long,
        val sessionTtlMinutes: Long,
    ) {
        /** The fully-expanded WebSocket URI the connector dials, including the `/control` root. */
        fun controlUri(): String = normalize(url.value)

        companion object {
            private fun normalize(rawUrl: String): String {
                val trimmed = rawUrl.trim().trimEnd('/')
                val withScheme = when {
                    trimmed.startsWith("wss://") || trimmed.startsWith("ws://") -> trimmed
                    trimmed.startsWith("https://") -> "wss://" + trimmed.removePrefix("https://")
                    trimmed.startsWith("http://") -> "ws://" + trimmed.removePrefix("http://")
                    else -> "wss://$trimmed"
                }
                return withScheme + CloudClientConfig.WS_ROOT
            }
        }
    }

    companion object {
        private const val HANDSHAKE_TIMEOUT_SECONDS = 30L
        private const val STOP_JOIN_TIMEOUT_MILLIS = 5_000L
    }
}
