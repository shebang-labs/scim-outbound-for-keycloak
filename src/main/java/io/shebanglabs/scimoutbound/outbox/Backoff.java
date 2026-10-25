/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 Shebang Labs
 */
package io.shebanglabs.scimoutbound.outbox;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.random.RandomGenerator;

/**
 * Retry schedule with jitter (design report section 8.2): 30 s, 1 min, 2 min, 5 min, 10 min,
 * 30 min, then hourly. A server-provided {@code Retry-After} always wins.
 */
public class Backoff {

    static final List<Duration> SCHEDULE = List.of(
            Duration.ofSeconds(30), Duration.ofMinutes(1), Duration.ofMinutes(2), Duration.ofMinutes(5),
            Duration.ofMinutes(10), Duration.ofMinutes(30), Duration.ofHours(1));

    /** Jitter range as a fraction of the delay, applied in both directions. */
    static final double JITTER = 0.2;

    private final RandomGenerator random;

    public Backoff(RandomGenerator random) {
        this.random = random;
    }

    /**
     * @param failedAttempts number of attempts made so far (1 after the first failure)
     * @param retryAfter     delay requested by the server, if any
     */
    public Duration next(int failedAttempts, Optional<Duration> retryAfter) {
        if (retryAfter.isPresent()) {
            return retryAfter.get();
        }
        int index = Math.max(0, Math.min(failedAttempts - 1, SCHEDULE.size() - 1));
        long base = SCHEDULE.get(index).toMillis();
        double factor = 1 + (random.nextDouble() * 2 - 1) * JITTER;
        return Duration.ofMillis(Math.round(base * factor));
    }
}
