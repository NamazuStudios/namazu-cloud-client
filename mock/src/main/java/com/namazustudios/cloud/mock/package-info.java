// This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
// If a copy of the MPL was not distributed with this file, You can obtain one at
// https://mozilla.org/MPL/2.0/.

// Development-only Element: the mock control plane (@ServerEndpoint) used by the SDK-local harness.
@ElementDefinition(recursive = true)
@GuiceElementModule(MockControlPlaneModule.class)
@ElementDependency("dev.getelements.elements.sdk")
package com.namazustudios.cloud.mock;

import com.namazustudios.cloud.mock.guice.MockControlPlaneModule;
import dev.getelements.elements.sdk.annotation.ElementDefinition;
import dev.getelements.elements.sdk.annotation.ElementDependency;
import dev.getelements.elements.sdk.spi.guice.annotations.GuiceElementModule;