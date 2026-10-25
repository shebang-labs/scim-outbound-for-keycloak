/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 Shebang Labs
 */
package io.shebanglabs.scimoutbound.worker;

import io.shebanglabs.scimoutbound.outbox.ResourceType;

/**
 * A change to the ID map produced while talking to a target, applied in the result transaction.
 *
 * @param remoteId the new remote id, or {@code null} to remove the mapping
 */
public record MappingChange(ResourceType type, String localId, String remoteId) {

    public static MappingChange link(ResourceType type, String localId, String remoteId) {
        return new MappingChange(type, localId, remoteId);
    }

    public static MappingChange unlink(ResourceType type, String localId) {
        return new MappingChange(type, localId, null);
    }
}
