/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 Shebang Labs
 */
package io.shebanglabs.scimoutbound.scim;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * SCIM Group resource (RFC 7643 section 4.2).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record ScimGroup(List<String> schemas, String id, String externalId, String displayName,
        List<ScimMember> members) {

    public static ScimGroup create(String externalId, String displayName, List<ScimMember> members) {
        return new ScimGroup(List.of(ScimSchemas.GROUP), null, externalId, displayName, members);
    }
}
