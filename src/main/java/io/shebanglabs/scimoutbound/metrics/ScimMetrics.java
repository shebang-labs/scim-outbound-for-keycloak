/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 Shebang Labs
 */
package io.shebanglabs.scimoutbound.metrics;

/**
 * Metrics sink. Labels are target ids, operations and outcomes only, never personal data.
 */
public interface ScimMetrics {

    /** An outbox row finished processing with the given result (done, retry, failed, superseded). */
    void rowProcessed(String targetId, String operation, String result, long durationMillis);

    /** Snapshot of the queue, refreshed by the worker. */
    void queueSnapshot(long openRows, long oldestOpenAgeMillis);

    /** A reconciliation finished. */
    void reconciled(String targetId, int creates, int updates, int removals, boolean blocked);

    ScimMetrics NOOP = new ScimMetrics() {
        @Override
        public void rowProcessed(String targetId, String operation, String result, long durationMillis) {
        }

        @Override
        public void queueSnapshot(long openRows, long oldestOpenAgeMillis) {
        }

        @Override
        public void reconciled(String targetId, int creates, int updates, int removals, boolean blocked) {
        }
    };
}
