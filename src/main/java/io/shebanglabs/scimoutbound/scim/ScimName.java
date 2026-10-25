/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 Shebang Labs
 */
package io.shebanglabs.scimoutbound.scim;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * The {@code name} complex attribute of a SCIM User (RFC 7643 section 4.1.1).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record ScimName(String givenName, String familyName, String formatted) {

    public static ScimName of(String givenName, String familyName) {
        if (givenName == null && familyName == null) {
            return null;
        }
        return new ScimName(givenName, familyName, null);
    }
}
