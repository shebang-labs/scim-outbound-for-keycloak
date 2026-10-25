/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 Shebang Labs
 */
package io.shebanglabs.scimoutbound.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Optional;
import java.util.random.RandomGenerator;
import org.junit.jupiter.api.Test;

class BackoffTest {

    /** Returns the given value from nextDouble so jitter is deterministic. */
    private static RandomGenerator fixed(double value) {
        return new RandomGenerator() {
            @Override
            public long nextLong() {
                return 0;
            }

            @Override
            public double nextDouble() {
                return value;
            }
        };
    }

    @Test
    void followsDocumentedScheduleWithoutJitter() {
        Backoff backoff = new Backoff(fixed(0.5));

        assertThat(backoff.next(1, Optional.empty())).isEqualTo(Duration.ofSeconds(30));
        assertThat(backoff.next(2, Optional.empty())).isEqualTo(Duration.ofMinutes(1));
        assertThat(backoff.next(3, Optional.empty())).isEqualTo(Duration.ofMinutes(2));
        assertThat(backoff.next(4, Optional.empty())).isEqualTo(Duration.ofMinutes(5));
        assertThat(backoff.next(5, Optional.empty())).isEqualTo(Duration.ofMinutes(10));
        assertThat(backoff.next(6, Optional.empty())).isEqualTo(Duration.ofMinutes(30));
        assertThat(backoff.next(7, Optional.empty())).isEqualTo(Duration.ofHours(1));
        assertThat(backoff.next(50, Optional.empty())).isEqualTo(Duration.ofHours(1));
    }

    @Test
    void jitterStaysWithinTwentyPercent() {
        assertThat(new Backoff(fixed(0.0)).next(1, Optional.empty())).isEqualTo(Duration.ofSeconds(24));
        assertThat(new Backoff(fixed(0.999999)).next(1, Optional.empty()))
                .isBetween(Duration.ofMillis(35_990), Duration.ofSeconds(36));
    }

    @Test
    void retryAfterWins() {
        assertThat(new Backoff(fixed(0.5)).next(1, Optional.of(Duration.ofSeconds(3))))
                .isEqualTo(Duration.ofSeconds(3));
    }
}
