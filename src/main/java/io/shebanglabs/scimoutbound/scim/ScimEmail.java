/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 Shebang Labs
 */
package io.shebanglabs.scimoutbound.scim;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * One entry of the multi-valued {@code emails} attribute.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record ScimEmail(String value, String type, Boolean primary) {

    public static ScimEmail work(String value) {
        return new ScimEmail(value, "work", Boolean.TRUE);
    }
}
