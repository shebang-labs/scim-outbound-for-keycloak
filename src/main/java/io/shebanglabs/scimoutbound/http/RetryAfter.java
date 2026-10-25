/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 Shebang Labs
 */
package io.shebanglabs.scimoutbound.http;

import java.time.Clock;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Optional;

/**
 * Parses the {@code Retry-After} header (RFC 9110 section 10.2.3): either delta-seconds or an
 * HTTP date. Values are capped so a hostile server cannot park work for days.
 */
public final class RetryAfter {

    static final Duration MAX = Duration.ofHours(6);

    private RetryAfter() {
    }

    public static Optional<Duration> parse(String header, Clock clock) {
        if (header == null || header.isBlank()) {
            return Optional.empty();
        }
        String value = header.trim();
        try {
            long seconds = Long.parseLong(value);
            return Optional.of(cap(Duration.ofSeconds(Math.max(0, seconds))));
        } catch (NumberFormatException ignored) {
            // not delta-seconds, try an HTTP date
        }
        try {
            ZonedDateTime at = ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME);
            Duration d = Duration.between(clock.instant(), at.toInstant());
            return Optional.of(cap(d.isNegative() ? Duration.ZERO : d));
        } catch (DateTimeParseException e) {
            return Optional.empty();
        }
    }

    private static Duration cap(Duration d) {
        return d.compareTo(MAX) > 0 ? MAX : d;
    }
}
