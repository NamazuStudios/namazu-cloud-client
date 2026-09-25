// This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
// If a copy of the MPL was not distributed with this file, You can obtain one at
// https://mozilla.org/MPL/2.0/.

import com.namazustudios.cloud.config.CloudClientConfig
import dev.getelements.elements.sdk.local.ElementsLocalBuilder
import java.io.File
import java.util.Properties

/**
 * Runs the client element + mock control plane in the local SDK.
 *
 * Working directory must be the project root (namazu-cloud-client). Overrides go in
 * debug/local.properties (gitignored); the stock defaults mirror the mock control plane's own
 * default secret, so the harness works without configuration:
 *
 *     com.namazustudios.cloud.client.secret=dev-secret
 *     com.namazustudios.cloud.client.url=ws://localhost:8080/ws/cloud-control
 */
fun main() {

    ProcessBuilder("docker", "compose", "up", "-d")
        .directory(File("services-dev"))
        .inheritIO()
        .start()
        .waitFor()

    val props = Properties()
    File("debug/local.properties").takeIf { it.exists() }
        ?.inputStream()?.use { props.load(it) }

    val secret = props.getProperty(CloudClientConfig.SECRET, "dev-secret")
    val url = props.getProperty(CloudClientConfig.URL, "ws://localhost:8080/ws/cloud-control")
    val id = props.getProperty(CloudClientConfig.ID, "local-debug-instance")

    val local = ElementsLocalBuilder.getDefault()
        .withSourceRoot()
        .withDeployment { builder ->
            builder
                .useDefaultRepositories(true)
                .elementPackage()
                .pathAttributes(
                    mapOf(
                        "com.namazustudios.cloud.element" to mapOf(
                            CloudClientConfig.URL to url,
                            CloudClientConfig.ID to id,
                            CloudClientConfig.SECRET to secret,
                        )
                    )
                )
                .elmArtifact("com.namazustudios.cloud:element:elm:0.1.0-SNAPSHOT")
                .endElementPackage()
                .elementPackage()
                .pathAttributes(
                    mapOf(
                        "com.namazustudios.cloud.mock" to mapOf(
                            CloudClientConfig.SECRET to secret,
                            // Pin the mock plane's WS context so the client URL above is stable.
                            "dev.getelements.elements.element.ws.root" to "/ws/cloud-control",
                        )
                    )
                )
                .elmArtifact("com.namazustudios.cloud:mock:elm:0.1.0-SNAPSHOT")
                .endElementPackage()
                .build()
        }
        .build()

    local.start()
    local.run()

}