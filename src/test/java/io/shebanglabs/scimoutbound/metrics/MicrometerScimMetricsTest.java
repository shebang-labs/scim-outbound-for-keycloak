/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 Shebang Labs
 */
package io.shebanglabs.scimoutbound.metrics;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

class MicrometerScimMetricsTest {

    @Test
    void recordsRowsQueueAndReconciliation() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        MicrometerScimMetrics metrics = new MicrometerScimMetrics(registry);

        metrics.rowProcessed("t1", "UPSERT", "done", 12);
        metrics.rowProcessed("t1", "UPSERT", "done", 8);
        metrics.rowProcessed("t1", "DELETE", "retry", 30);
        metrics.queueSnapshot(7, 125_000);
        metrics.reconciled("t1", 3, 2, 1, false);

        assertThat(registry.get("scim_outbound_rows_total").tags("target", "t1", "operation", "UPSERT", "result", "done")
                .counter().count()).isEqualTo(2);
        assertThat(registry.get("scim_outbound_row_duration").tag("result", "done").timer().count()).isEqualTo(2);
        assertThat(registry.get("scim_outbound_queue_open").gauge().value()).isEqualTo(7);
        assertThat(registry.get("scim_outbound_queue_oldest_age_seconds").gauge().value()).isEqualTo(125);
        assertThat(registry.get("scim_outbound_reconcile_changes_total").tags("kind", "create").counter().count())
                .isEqualTo(3);
    }

    @Test
    void factoryFallsBackGracefully() {
        assertThat(MicrometerScimMetrics.createIfAvailable()).isNotNull();
    }
}
