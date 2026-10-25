/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 Shebang Labs
 */
package io.shebanglabs.scimoutbound.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Publishes plugin metrics to Micrometer, which Keycloak exposes on its management interface
 * ({@code /metrics}) when {@code --metrics-enabled=true}.
 *
 * <ul>
 *   <li>{@code scim_outbound_rows_total{target,operation,result}}: processed outbox rows</li>
 *   <li>{@code scim_outbound_row_duration_seconds{result}}: time to process a row</li>
 *   <li>{@code scim_outbound_queue_open}: rows waiting or in progress</li>
 *   <li>{@code scim_outbound_queue_oldest_age_seconds}: age of the oldest open row (key for
 *       access-removal delay)</li>
 *   <li>{@code scim_outbound_reconcile_total{target,blocked}} and
 *       {@code scim_outbound_reconcile_changes_total{target,kind}}</li>
 * </ul>
 */
public class MicrometerScimMetrics implements ScimMetrics {

    private final MeterRegistry registry;
    private final AtomicLong open = new AtomicLong();
    private final AtomicLong oldestAgeSeconds = new AtomicLong();

    public MicrometerScimMetrics() {
        this(Metrics.globalRegistry);
    }

    public MicrometerScimMetrics(MeterRegistry registry) {
        this.registry = registry;
        Gauge.builder("scim_outbound_queue_open", open, AtomicLong::get)
                .description("Outbox rows waiting to be sent or in progress")
                .register(registry);
        Gauge.builder("scim_outbound_queue_oldest_age_seconds", oldestAgeSeconds, AtomicLong::get)
                .description("Age of the oldest open outbox row")
                .baseUnit("seconds")
                .register(registry);
    }

    /** Returns Micrometer metrics when Micrometer is on the classpath, otherwise a no-op sink. */
    public static ScimMetrics createIfAvailable() {
        try {
            Class.forName("io.micrometer.core.instrument.Metrics", false, MicrometerScimMetrics.class.getClassLoader());
            return new MicrometerScimMetrics();
        } catch (ClassNotFoundException | LinkageError e) {
            return ScimMetrics.NOOP;
        }
    }

    @Override
    public void rowProcessed(String targetId, String operation, String result, long durationMillis) {
        Counter.builder("scim_outbound_rows_total")
                .description("Processed outbox rows")
                .tag("target", targetId).tag("operation", operation).tag("result", result)
                .register(registry).increment();
        Timer.builder("scim_outbound_row_duration")
                .description("Time to process one outbox row")
                .tag("result", result)
                .register(registry).record(Duration.ofMillis(durationMillis));
    }

    @Override
    public void queueSnapshot(long openRows, long oldestOpenAgeMillis) {
        open.set(openRows);
        oldestAgeSeconds.set(oldestOpenAgeMillis / 1000);
    }

    @Override
    public void reconciled(String targetId, int creates, int updates, int removals, boolean blocked) {
        Counter.builder("scim_outbound_reconcile_total")
                .description("Reconciliation runs")
                .tag("target", targetId).tag("blocked", Boolean.toString(blocked))
                .register(registry).increment();
        change(targetId, "create", creates);
        change(targetId, "update", updates);
        change(targetId, "removal", removals);
    }

    private void change(String targetId, String kind, int count) {
        Counter.builder("scim_outbound_reconcile_changes_total")
                .description("Differences found by reconciliation")
                .tag("target", targetId).tag("kind", kind)
                .register(registry).increment(count);
    }
}
