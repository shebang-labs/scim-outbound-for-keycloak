/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 Shebang Labs
 */
package io.shebanglabs.scimoutbound.scim;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * Query response (RFC 7644 section 3.4.2).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record ScimListResponse<T>(
        List<String> schemas,
        Integer totalResults,
        Integer startIndex,
        Integer itemsPerPage,
        @JsonProperty("Resources") List<T> resources) {

    public List<T> resourcesOrEmpty() {
        return resources == null ? List.of() : resources;
    }

    public int total() {
        return totalResults == null ? resourcesOrEmpty().size() : totalResults;
    }
}
