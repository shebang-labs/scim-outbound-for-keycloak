/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 Shebang Labs
 */
package io.shebanglabs.scimoutbound.config;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;

/**
 * Server-wide settings chosen by the Keycloak operator (not by realm admins). They are read once
 * at startup from {@code --spi-scim-outbound--default--<option>} options.
 *
 * @param allowedHosts            host allowlist for target and token URLs; entries are exact host
 *                                names or {@code *.suffix} wildcards. Empty means any public host.
 * @param allowHttp               allow plain {@code http://} targets (for local testing only)
 * @param allowPrivateAddresses   allow targets resolving to loopback, private or link-local addresses
 * @param allowPlaintextSecrets   accept secrets that are not {@code ${vault.*}} references
 * @param workerInterval          how often the outbox worker runs
 * @param batchSize               maximum rows claimed per worker round
 * @param reconcileInterval       how often scheduled reconciliation runs; zero disables it
 * @param connectTimeout          TCP connect timeout for SCIM calls
 * @param readTimeout             socket read timeout for SCIM calls
 * @param maxResponseBytes        responses larger than this are rejected
 * @param maxRetryAge             a row still failing after this long becomes FAILED
 * @param claimTimeout            IN_PROGRESS rows older than this are released for another attempt
 * @param retention               DONE and SUPERSEDED rows older than this are deleted
 * @param failedRetention         FAILED rows older than this are deleted
 * @param vaultKeyPrefix          vault keys targets may reference must start with this, so that a
 *                                realm admin cannot send other vault secrets of the realm (LDAP
 *                                bind password, identity provider secrets) to a SCIM server;
 *                                {@code *} allows any key
 */
public record ServerConfig(
        List<String> allowedHosts,
        boolean allowHttp,
        boolean allowPrivateAddresses,
        boolean allowPlaintextSecrets,
        Duration workerInterval,
        int batchSize,
        Duration reconcileInterval,
        Duration connectTimeout,
        Duration readTimeout,
        long maxResponseBytes,
        Duration maxRetryAge,
        Duration claimTimeout,
        Duration retention,
        Duration failedRetention,
        String vaultKeyPrefix) {

    public static final String ALLOWED_HOSTS = "allowed-hosts";
    public static final String ALLOW_HTTP = "allow-http";
    public static final String ALLOW_PRIVATE_ADDRESSES = "allow-private-addresses";
    public static final String ALLOW_PLAINTEXT_SECRETS = "allow-plaintext-secrets";
    public static final String WORKER_INTERVAL_SECONDS = "worker-interval-seconds";
    public static final String BATCH_SIZE = "batch-size";
    public static final String RECONCILE_INTERVAL_MINUTES = "reconcile-interval-minutes";
    public static final String CONNECT_TIMEOUT_MS = "connect-timeout-ms";
    public static final String READ_TIMEOUT_MS = "read-timeout-ms";
    public static final String MAX_RESPONSE_BYTES = "max-response-bytes";
    public static final String MAX_RETRY_HOURS = "max-retry-hours";
    public static final String CLAIM_TIMEOUT_SECONDS = "claim-timeout-seconds";
    public static final String RETENTION_DAYS = "retention-days";
    public static final String FAILED_RETENTION_DAYS = "failed-retention-days";
    public static final String VAULT_KEY_PREFIX = "vault-key-prefix";
    static final String DEFAULT_VAULT_KEY_PREFIX = "scim-";

    public ServerConfig {
        allowedHosts = allowedHosts == null ? List.of() : List.copyOf(allowedHosts);
        if (batchSize < 1) {
            throw new IllegalArgumentException(BATCH_SIZE + " must be at least 1");
        }
        if (workerInterval.isNegative() || workerInterval.isZero()) {
            throw new IllegalArgumentException(WORKER_INTERVAL_SECONDS + " must be positive");
        }
    }

    /** Secure defaults, matching the documentation. */
    public static ServerConfig defaults() {
        return fromLookup(key -> null);
    }

    /**
     * Builds the configuration from a key lookup (normally Keycloak's {@code Config.Scope::get}).
     * Missing keys fall back to the documented defaults.
     */
    public static ServerConfig fromLookup(Function<String, String> lookup) {
        return new ServerConfig(
                list(lookup.apply(ALLOWED_HOSTS)),
                bool(lookup.apply(ALLOW_HTTP), false),
                bool(lookup.apply(ALLOW_PRIVATE_ADDRESSES), false),
                bool(lookup.apply(ALLOW_PLAINTEXT_SECRETS), false),
                Duration.ofSeconds(number(lookup.apply(WORKER_INTERVAL_SECONDS), 5)),
                (int) number(lookup.apply(BATCH_SIZE), 50),
                Duration.ofMinutes(number(lookup.apply(RECONCILE_INTERVAL_MINUTES), 1440)),
                Duration.ofMillis(number(lookup.apply(CONNECT_TIMEOUT_MS), 5_000)),
                Duration.ofMillis(number(lookup.apply(READ_TIMEOUT_MS), 30_000)),
                number(lookup.apply(MAX_RESPONSE_BYTES), 5L * 1024 * 1024),
                Duration.ofHours(number(lookup.apply(MAX_RETRY_HOURS), 24)),
                Duration.ofSeconds(number(lookup.apply(CLAIM_TIMEOUT_SECONDS), 300)),
                Duration.ofDays(number(lookup.apply(RETENTION_DAYS), 7)),
                Duration.ofDays(number(lookup.apply(FAILED_RETENTION_DAYS), 30)),
                text(lookup.apply(VAULT_KEY_PREFIX), DEFAULT_VAULT_KEY_PREFIX));
    }

    private static List<String> list(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        return Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(s -> s.toLowerCase(Locale.ROOT))
                .toList();
    }

    /** Whether a target may reference this vault key. */
    public boolean vaultKeyAllowed(String key) {
        return "*".equals(vaultKeyPrefix) || (key != null && key.startsWith(vaultKeyPrefix));
    }

    private static String text(String raw, String fallback) {
        return raw == null || raw.isBlank() ? fallback : raw.trim();
    }

    private static boolean bool(String raw, boolean fallback) {
        return raw == null || raw.isBlank() ? fallback : Boolean.parseBoolean(raw.trim());
    }

    private static long number(String raw, long fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            long value = Long.parseLong(raw.trim());
            if (value < 0) {
                throw new IllegalArgumentException("negative value: " + raw);
            }
            return value;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("not a number: " + raw, e);
        }
    }
}
