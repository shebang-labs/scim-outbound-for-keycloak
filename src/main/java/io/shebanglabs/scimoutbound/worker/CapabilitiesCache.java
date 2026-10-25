/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 Shebang Labs
 */
package io.shebanglabs.scimoutbound.worker;

import io.shebanglabs.scimoutbound.config.ScimTarget;
import io.shebanglabs.scimoutbound.http.ScimClient;
import io.shebanglabs.scimoutbound.http.ScimException;
import io.shebanglabs.scimoutbound.scim.ScimServiceProviderConfig;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Caches each target's {@code /ServiceProviderConfig} so the client knows whether PATCH is
 * supported without asking on every request.
 */
public class CapabilitiesCache {

    static final Duration TTL = Duration.ofHours(1);

    private record Entry(ScimServiceProviderConfig config, Instant fetchedAt) {
    }

    private final Map<String, Entry> entries = new ConcurrentHashMap<>();
    private final Clock clock;

    public CapabilitiesCache(Clock clock) {
        this.clock = clock;
    }

    public ScimServiceProviderConfig get(ScimTarget target, ScimClient client) throws ScimException {
        String key = target.id() + ":" + target.updatedAt();
        Entry entry = entries.get(key);
        if (entry != null && clock.instant().isBefore(entry.fetchedAt().plus(TTL))) {
            return entry.config();
        }
        ScimServiceProviderConfig config = client.serviceProviderConfig();
        entries.keySet().removeIf(k -> k.startsWith(target.id() + ":"));
        entries.put(key, new Entry(config, clock.instant()));
        return config;
    }

    public void evict(String targetId) {
        entries.keySet().removeIf(k -> k.startsWith(targetId + ":"));
    }
}
