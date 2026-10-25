/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 Shebang Labs
 */
package io.shebanglabs.scimoutbound.outbox;

/**
 * Kind of Keycloak object an outbox row or ID mapping refers to.
 */
public enum ResourceType {
    USER,
    GROUP
}
