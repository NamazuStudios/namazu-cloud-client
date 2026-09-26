// This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
// If a copy of the MPL was not distributed with this file, You can obtain one at
// https://mozilla.org/MPL/2.0/.

package com.namazustudios.cloud.element.service

import com.namazustudios.cloud.element.rest.detail
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Covers the two pieces of the dashboard's status surface that are pure functions of their input:
 * the wire-name mapping used for persistence, and the control-URI expansion shown in the readout.
 *
 * The enable/disable state machine itself is exercised end-to-end by the `debug` module's
 * loopback against the mock control plane rather than here — it needs a real socket and a
 * Morphia datastore, and a fake would only assert the fake.
 */
class CloudConnectStateTest {

    @Test
    fun `wire names round-trip`() {
        for (state in CloudConnectState.entries) {
            assertEquals(state, CloudConnectState.fromWireName(state.wireName), "round-trip for ${state.wireName}")
        }
    }

    @Test
    fun `unknown or absent persisted state falls back to DISCONNECTED`() {
        // A document written by a newer build must not break an older one's dashboard.
        assertEquals(CloudConnectState.DISCONNECTED, CloudConnectState.fromWireName(null))
        assertEquals(CloudConnectState.DISCONNECTED, CloudConnectState.fromWireName("SOMETHING_NEW"))
        assertEquals(CloudConnectState.DISCONNECTED, CloudConnectState.fromWireName(""))
    }

    @Test
    fun `every state has a distinct human-readable detail`() {
        val details = CloudConnectState.entries.map { it.detail() }
        assertEquals(details.size, details.distinct().size, "detail text must be unique per state")
        details.forEach { assertTrue(it.isNotBlank()) }
    }

    @Test
    fun `control URI appends the control root and upgrades bare hosts to wss`() {
        assertEquals("wss://cloud.namazustudios.com/control", config("cloud.namazustudios.com").controlUri())
        assertEquals("wss://cloud.namazustudios.com/control", config("cloud.namazustudios.com/").controlUri())
    }

    @Test
    fun `control URI upgrades http and https to their ws equivalents`() {
        assertEquals("wss://cloud.example.com/control", config("https://cloud.example.com").controlUri())
        assertEquals("ws://localhost:8080/control", config("http://localhost:8080").controlUri())
    }

    @Test
    fun `control URI leaves explicit websocket schemes alone`() {
        assertEquals("wss://cloud.example.com/control", config("wss://cloud.example.com").controlUri())
        assertEquals("ws://localhost:8080/control", config("ws://localhost:8080").controlUri())
    }

    @Test
    fun `control URI does not double the slash before the control root`() {
        assertEquals("ws://localhost:8080/control", config("ws://localhost:8080///").controlUri())
    }

    @Test
    fun `websocket and http forms of the same host stay distinguishable`() {
        // Guards against a regression that would silently send production traffic over plaintext
        // because a configured host happened to be reused from the dev harness.
        assertNotEquals(config("ws://localhost:8080").controlUri(), config("wss://localhost:8080").controlUri())
    }

    private fun config(url: String) = CloudClientService.ResolvedConfig(
        url = CloudClientService.ResolvedValue(url, CloudClientService.ValueSource.ATTRIBUTE),
        id = CloudClientService.ResolvedValue("instance-1", CloudClientService.ValueSource.ATTRIBUTE),
        secret = CloudClientService.ResolvedValue("secret", CloudClientService.ValueSource.ATTRIBUTE),
        retrySeconds = 5,
        sessionTtlMinutes = 60,
    )
}
