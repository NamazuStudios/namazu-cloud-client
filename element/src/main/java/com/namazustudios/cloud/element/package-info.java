// This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
// If a copy of the MPL was not distributed with this file, You can obtain one at
// https://mozilla.org/MPL/2.0/.

// Required annotation for an Element. Recursively scans from this package for classes. The
// annotation-bearing package-info must live in the element's root package so the SDK loader
// discovers CloudClientModule and the wire-protocol elements it bundles.
@ElementDefinition(recursive = true)
@GuiceElementModule(CloudClientModule.class)
@ElementDependency("dev.getelements.elements.sdk")
@ElementDependency("dev.getelements.elements.sdk.dao")
@ElementDependency("dev.getelements.elements.sdk.service")
@ElementPackageRequest("java.net.http")
package com.namazustudios.cloud.element;

import com.namazustudios.cloud.element.guice.CloudClientModule;
import dev.getelements.elements.sdk.annotation.ElementDefinition;
import dev.getelements.elements.sdk.annotation.ElementDependency;
import dev.getelements.elements.sdk.annotation.ElementPackageRequest;
import dev.getelements.elements.sdk.spi.guice.annotations.GuiceElementModule;