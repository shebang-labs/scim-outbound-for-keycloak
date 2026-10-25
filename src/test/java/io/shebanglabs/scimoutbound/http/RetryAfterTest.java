/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 Shebang Labs
 */
package io.shebanglabs.scimoutbound.http;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class RetryAfterTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-25T10:00:00Z"), ZoneOffset.UTC);

    @Test
    void parsesDeltaSeconds() {
        assertThat(RetryAfter.parse("120", CLOCK)).contains(Duration.ofMinutes(2));
    }

    @Test
    void parsesHttpDate() {
        assertThat(RetryAfter.parse("Sun, 25 Oct 2026 10:05:00 GMT", CLOCK)).contains(Duration.ofMinutes(5));
    }

    @Test
    void pastDateMeansNow() {
        assertThat(RetryAfter.parse("Sun, 25 Oct 2026 09:00:00 GMT", CLOCK)).contains(Duration.ZERO);
    }

    @Test
    void capsHostileValues() {
        assertThat(RetryAfter.parse("99999999", CLOCK)).contains(RetryAfter.MAX);
    }

    @Test
    void ignoresGarbage() {
        assertThat(RetryAfter.parse("soon", CLOCK)).isEmpty();
        assertThat(RetryAfter.parse(null, CLOCK)).isEmpty();
        assertThat(RetryAfter.parse(" ", CLOCK)).isEmpty();
    }
}
