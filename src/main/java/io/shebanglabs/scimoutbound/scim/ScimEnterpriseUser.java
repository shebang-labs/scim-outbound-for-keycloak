/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 Shebang Labs
 */
package io.shebanglabs.scimoutbound.scim;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Enterprise User extension (RFC 7643 section 4.3). The {@code manager} attribute is not
 * supported because it needs a remote id lookup of another user.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record ScimEnterpriseUser(
        String employeeNumber,
        String costCenter,
        String organization,
        String division,
        String department) {

    @JsonIgnore
    public boolean isEmpty() {
        return employeeNumber == null && costCenter == null && organization == null
                && division == null && department == null;
    }
}
