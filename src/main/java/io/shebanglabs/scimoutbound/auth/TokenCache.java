/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 Shebang Labs
 */
package io.shebanglabs.scimoutbound.auth;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory, per-node cache of OAuth access tokens. Tokens are never persisted. A token is
 * treated as expired {@link #EARLY_REFRESH} before its real expiry.
 */
public class TokenCache {

    static final Duration EARLY_REFRESH = Duration.ofSeconds(60);

    private record Entry(String token, Instant expiresAt) {
    }

    private final Map<String, Entry> entries = new ConcurrentHashMap<>();
    private final Clock clock;

    public TokenCache(Clock clock) {
        this.clock = clock;
    }

    public Optional<String> get(String key) {
        Entry entry = entries.get(key);
        if (entry == null || !clock.instant().isBefore(entry.expiresAt().minus(EARLY_REFRESH))) {
            return Optional.empty();
        }
        return Optional.of(entry.token());
    }

    public void put(String key, String token, Duration lifetime) {
        entries.put(key, new Entry(token, clock.instant().plus(lifetime)));
    }

    public void evict(String key) {
        entries.remove(key);
    }

    /** Removes every token of a target, e.g. after its configuration changed. */
    public void evictPrefix(String prefix) {
        entries.keySet().removeIf(k -> k.startsWith(prefix));
    }
}
