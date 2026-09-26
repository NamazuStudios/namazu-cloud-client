// This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
// If a copy of the MPL was not distributed with this file, You can obtain one at
// https://mozilla.org/MPL/2.0/.

// Required annotation for an Element. Recursively scans from this package for classes. The
// annotation-bearing package-info must live in the element's root package so the SDK loader
// discovers CloudClientModule and the wire-protocol elements it bundles.
@ElementDefinition(recursive = true)
@GuiceElementModule(CloudClientModule.class)
// EntityRegistry is exported from CloudConnectEntityRegistry via @ElementServiceExport; a
// package-level @ElementService for it would be redundant.
@ElementDependency("dev.getelements.elements.sdk")
@ElementDependency("dev.getelements.elements.sdk.dao")
@ElementDependency("dev.getelements.elements.sdk.service")
// Supplies the shared Morphia Datastore backing the dashboard's persisted enable/disable switch.
@ElementDependency("dev.getelements.elements.sdk.mongo")
@ElementPackageRequest("java.net.http")
// Grants classloader visibility to dev.morphia.*, com.mongodb.* and org.bson.*, which the
// CloudConnectStateDocument entity and CloudConnectStateDao need.
@ElementPackageRequest(request = MorphiaPackageRequest.class)
package com.namazustudios.cloud.element;

import com.namazustudios.cloud.element.guice.CloudClientModule;
import dev.getelements.elements.sdk.annotation.ElementDefinition;
import dev.getelements.elements.sdk.annotation.ElementDependency;
import dev.getelements.elements.sdk.annotation.ElementPackageRequest;
import dev.getelements.elements.sdk.dao.annotation.MorphiaPackageRequest;
import dev.getelements.elements.sdk.spi.guice.annotations.GuiceElementModule;
