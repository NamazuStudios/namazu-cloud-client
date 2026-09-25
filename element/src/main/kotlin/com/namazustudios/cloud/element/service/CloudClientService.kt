// This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
// If a copy of the MPL was not distributed with this file, You can obtain one at
// https://mozilla.org/MPL/2.0/.

package com.namazustudios.cloud.element.service

import com.google.inject.name.Named
import com.namazustudios.cloud.config.CloudClientConfig
import com.namazustudios.cloud.element.CloudClientAttributes
import com.namazustudios.cloud.element.channel.ControlChannel
import com.namazustudios.cloud.element.transport.WebSocketTransportPicker
import com.namazustudios.cloud.protocol.ControlMac
import jakarta.inject.Inject
import jakarta.inject.Singleton
import org.slf4j.LoggerFactory
import java.net.URI
import java.util.concurrent.TimeUnit

/**
 * Owns the control channel lifecycle. Binds eagerly (via [CloudClientModule]) so the connector
 * starts dialing out as soon as the element loads. When the instance has no client id/secret
 * configured (or only env/attribute values are missing), the channel stays idle and logs a warning.
 *
 * Config precedence per key: `System.getenv(<envKey>)` wins over the element attribute; the
 * attribute defaults are declared in [CloudClientAttributes]. This matches the proxy convention
 * that Conductor-injected task environments carry `com_namazustudios_cloud_client_*` values.
 */
@Singleton
class CloudClientService @Inject constructor(
    private val dispatcher: ControlCommandDispatcher,
) {
    private val log = LoggerFactory.getLogger(CloudClientService::class.java)

    @Volatile private var running = false

    /**
     * Guice method-injection entry point: resolves configuration and launches the reconnect loop on
     * a daemon thread. Called once at element load.
     */
    @Inject
    fun start(
        @Named(CloudClientAttributes.URL) urlAttr: String,
        @Named(CloudClientAttributes.ID) idAttr: String,
        @Named(CloudClientAttributes.SECRET) secretAttr: String,
        @Named(CloudClientAttributes.RETRY_SECONDS) retryAttr: String,
    ) {
        val url = envOr(CloudClientConfig.URL_ENV, urlAttr)
        val id = envOr(CloudClientConfig.ID_ENV, idAttr)
        val secret = envOr(CloudClientConfig.SECRET_ENV, secretAttr)
        val retrySeconds = envOr(CloudClientConfig.RETRY_SECONDS_ENV, retryAttr)
            .toLongOrNull() ?: CloudClientConfig.RETRY_SECONDS_DEFAULT

        if (id.isBlank() || secret.isBlank()) {
            log.warn(
                "Namazu Cloud client not configured (id {} / secret {}); cloud-control channel is DISABLED. " +
                    "Set {} and {} attributes or their {} env vars.",
                if (id.isBlank()) "MISSING" else "set",
                if (secret.isBlank()) "MISSING" else "set",
                CloudClientConfig.ID, CloudClientConfig.SECRET,
            )
            return
        }

        if (running) {
            log.warn("Namazu Cloud client already running; ignoring duplicate start")
            return
        }
        running = true

        val thread = Thread(
            { runLoop(url.trim(), id.trim(), secret, retrySeconds) },
            "namazu-cloud-control",
        )
        thread.isDaemon = true
        thread.start()
    }

    private fun runLoop(rawUrl: String, id: String, secret: String, retrySeconds: Long) {
        val key = ControlMac.deriveKey(secret)
        val url = normalizeUri(rawUrl)
        val transport = WebSocketTransportPicker { message, error ->
            if (error == null) log.info(message) else log.warn(message, error)
        }

        log.info("Namazu Cloud client connecting to {} as clientId {} (transport auto)", url, id)

        while (running) {
            val channel = ControlChannel(
                clientId = id,
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
            try {
                channel.open().get(HANDSHAKE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                log.info("control channel authenticated with Namazu Cloud")
                channel.awaitTerminal().get()
                log.info("control channel ran to completion")
            } catch (e: Exception) {
                log.warn("control channel unavailable: {}", e.message ?: e.javaClass.simpleName)
            } finally {
                dispatcher.channel = null
                runCatching { channel.abort() }
            }
            if (!running) break
            log.info("retrying control channel in {}s", retrySeconds)
            try {
                Thread.sleep(retrySeconds * 1000L)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                break
            }
        }
    }

    private fun envOr(envKey: String, fallback: String): String =
        System.getenv(envKey)?.takeIf { it.isNotBlank() } ?: fallback

    private fun normalizeUri(rawUrl: String): URI {
        val trimmed = rawUrl.trim().trimEnd('/')
        val withScheme = when {
            trimmed.startsWith("wss://") || trimmed.startsWith("ws://") -> trimmed
            trimmed.startsWith("https://") -> "wss://" + trimmed.removePrefix("https://")
            trimmed.startsWith("http://") -> "ws://" + trimmed.removePrefix("http://")
            else -> "wss://$trimmed"
        }
        return URI.create(withScheme + CloudClientConfig.WS_ROOT)
    }

    companion object {
        private const val HANDSHAKE_TIMEOUT_SECONDS = 30L
    }
}