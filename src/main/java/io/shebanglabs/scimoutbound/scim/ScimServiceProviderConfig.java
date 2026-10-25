/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 Shebang Labs
 */
package io.shebanglabs.scimoutbound.scim;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * The parts of {@code /ServiceProviderConfig} (RFC 7643 section 5) that change client behaviour.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ScimServiceProviderConfig(Supported patch, Filter filter) {

    /** A {@code {"supported": bool}} feature. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Supported(Boolean supported) {
    }

    /** The {@code filter} feature with its result cap. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Filter(Boolean supported, Integer maxResults) {
    }

    /** Defaults used when a server does not publish its configuration: PATCH and filter assumed. */
    public static final ScimServiceProviderConfig ASSUMED =
            new ScimServiceProviderConfig(new Supported(true), new Filter(true, null));

    public boolean patchSupported() {
        return patch == null || patch.supported() == null || patch.supported();
    }

    public boolean filterSupported() {
        return filter == null || filter.supported() == null || filter.supported();
    }
}
