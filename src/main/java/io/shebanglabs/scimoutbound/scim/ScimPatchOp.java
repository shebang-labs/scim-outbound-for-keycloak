/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 Shebang Labs
 */
package io.shebanglabs.scimoutbound.scim;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.ArrayList;
import java.util.List;

/**
 * PATCH request body (RFC 7644 section 3.5.2).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record ScimPatchOp(List<String> schemas, @JsonProperty("Operations") List<Operation> operations) {

    /** A single PATCH operation. {@code value} is serialised as-is. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Operation(String op, String path, Object value) {
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Fluent builder used by the sync code. */
    public static final class Builder {
        private final List<Operation> operations = new ArrayList<>();

        public Builder add(String path, Object value) {
            operations.add(new Operation("add", path, value));
            return this;
        }

        public Builder replace(String path, Object value) {
            operations.add(new Operation("replace", path, value));
            return this;
        }

        public Builder remove(String path) {
            operations.add(new Operation("remove", path, null));
            return this;
        }

        public boolean isEmpty() {
            return operations.isEmpty();
        }

        public ScimPatchOp build() {
            return new ScimPatchOp(List.of(ScimSchemas.PATCH_OP), List.copyOf(operations));
        }
    }
}
