/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 Shebang Labs
 */
package io.shebanglabs.scimoutbound.outbox;

import java.util.EnumSet;
import java.util.Set;

/**
 * States of an outbox row (design report, figure 7). Every path ends in DONE, FAILED or
 * SUPERSEDED, so nothing loops forever.
 */
public enum OutboxStatus {
    PENDING,
    IN_PROGRESS,
    RETRY_WAIT,
    DONE,
    FAILED,
    SUPERSEDED;

    public static final Set<OutboxStatus> OPEN = EnumSet.of(PENDING, IN_PROGRESS, RETRY_WAIT);
    public static final Set<OutboxStatus> CLAIMABLE = EnumSet.of(PENDING, RETRY_WAIT);
    public static final Set<OutboxStatus> TERMINAL = EnumSet.of(DONE, FAILED, SUPERSEDED);
}
