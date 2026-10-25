/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 Shebang Labs
 */
package io.shebanglabs.scimoutbound.scim;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Entry of a Group's {@code members} attribute. {@code value} is the remote id of the member.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record ScimMember(String value, String display, @JsonProperty("$ref") String ref, String type) {

    public static ScimMember of(String remoteId) {
        return new ScimMember(remoteId, null, null, null);
    }
}
