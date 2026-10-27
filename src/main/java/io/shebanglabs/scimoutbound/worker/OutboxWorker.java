/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 Shebang Labs
 */
package io.shebanglabs.scimoutbound.worker;

import io.shebanglabs.scimoutbound.auth.SecretResolver;
import io.shebanglabs.scimoutbound.config.InvalidConfigurationException;
import io.shebanglabs.scimoutbound.config.ScimTarget;
import io.shebanglabs.scimoutbound.config.ServerConfig;
import io.shebanglabs.scimoutbound.http.ScimClient;
import io.shebanglabs.scimoutbound.http.ScimException;
import io.shebanglabs.scimoutbound.jpa.IdMappingRepository;
import io.shebanglabs.scimoutbound.jpa.OutboxRepository;
import io.shebanglabs.scimoutbound.jpa.TargetRepository;
import io.shebanglabs.scimoutbound.metrics.ScimMetrics;
import io.shebanglabs.scimoutbound.outbox.Backoff;
import io.shebanglabs.scimoutbound.outbox.Operation;
import io.shebanglabs.scimoutbound.outbox.OutboxRow;
import io.shebanglabs.scimoutbound.outbox.OutboxStatus;
import jakarta.persistence.EntityManager;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import org.jboss.logging.Logger;
import org.keycloak.connections.jpa.JpaConnectionProvider;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;
import org.keycloak.models.RealmModel;
import org.keycloak.models.utils.KeycloakModelUtils;

/**
 * Drains the outbox. For every claimed row it uses three short, separate transactions and never
 * holds one open across network I/O (design report figure 4):
 * <ol>
 *   <li>Tx A: release stale claims, claim due rows;</li>
 *   <li>Tx B: load target, secrets and the current Keycloak state;</li>
 *   <li>no transaction: talk to the SCIM target;</li>
 *   <li>Tx C: save ID map changes and the row's new status.</li>
 * </ol>
 */
public class OutboxWorker {

    private static final Logger LOG = Logger.getLogger(OutboxWorker.class);
    static final Duration HOUSEKEEPING_INTERVAL = Duration.ofMinutes(10);
    /** How long rows of a target that just failed to answer wait before they are tried again. */
    static final Duration UNREACHABLE_PAUSE = Duration.ofSeconds(30);

    private final KeycloakSessionFactory factory;
    private final ServerConfig config;
    private final Clock clock;
    private final Backoff backoff;
    private final ClientFactory clients;
    private final CapabilitiesCache capabilities;
    private final ScimMetrics metrics;
    private final AtomicLong lastHousekeeping = new AtomicLong();

    public OutboxWorker(KeycloakSessionFactory factory, ServerConfig config, Clock clock, Backoff backoff,
            ClientFactory clients, CapabilitiesCache capabilities, ScimMetrics metrics) {
        this.factory = factory;
        this.config = config;
        this.clock = clock;
        this.backoff = backoff;
        this.clients = clients;
        this.capabilities = capabilities;
        this.metrics = metrics;
    }

    /** What to do with a row, decided in Tx B. */
    sealed interface Work {
        record Run(ScimTarget target, ScimClient client, Step step) implements Work {
        }

        record Skip(String reason) implements Work {
        }

        record Fail(String reason) implements Work {
        }
    }

    /** One executor call, with the state it needs. */
    @FunctionalInterface
    interface Step {
        void apply(SyncExecutor executor) throws ScimException;
    }

    /**
     * Processes due rows until none are left or the time budget is used up.
     *
     * @return number of rows processed
     */
    public int drain(Duration budget) {
        long deadline = clock.millis() + budget.toMillis();
        int processed = 0;
        housekeepingIfDue();
        refreshQueueMetrics();
        while (clock.millis() < deadline) {
            long claimedAt = clock.millis();
            List<OutboxRow> rows = KeycloakModelUtils.runJobInTransactionWithResult(factory, session -> {
                OutboxRepository outbox = new OutboxRepository(em(session));
                long now = clock.millis();
                int released = outbox.releaseStale(now - config.claimTimeout().toMillis(), now);
                if (released > 0) {
                    LOG.infof("released %d stale outbox claims", released);
                }
                return outbox.claimDue(now, config.batchSize());
            });
            if (rows.isEmpty()) {
                break;
            }
            // one target that times out must not hold up the others: the rest of its rows wait for
            // the next round, and rows still waiting when the claim gets old are released before
            // another node may take them over
            Set<String> unreachable = new HashSet<>();
            List<String> later = new ArrayList<>();
            List<String> released = new ArrayList<>();
            for (OutboxRow row : rows) {
                if (clock.millis() - claimedAt > config.claimTimeout().toMillis() / 2) {
                    released.add(row.id());
                } else if (unreachable.contains(row.targetId())) {
                    later.add(row.id());
                } else {
                    if (!process(row)) {
                        unreachable.add(row.targetId());
                    }
                    processed++;
                }
            }
            unclaim(later, UNREACHABLE_PAUSE);
            unclaim(released, Duration.ZERO);
        }
        refreshQueueMetrics();
        return processed;
    }

    /** @return {@code false} when the target did not answer (network error, timeout or 429) */
    boolean process(OutboxRow row) {
        long started = clock.millis();
        Work work;
        try {
            work = KeycloakModelUtils.runJobInTransactionWithResult(factory, session -> prepare(session, row));
        } catch (RuntimeException e) {
            LOG.debugf(e, "preparing outbox row %s failed", row.id());
            finish(row, null, Optional.of(new ScimException("could not load data: " + e.getClass().getSimpleName(),
                    e, true)), started);
            return true;
        }
        switch (work) {
            case Work.Skip skip -> {
                complete(row, OutboxStatus.SUPERSEDED, skip.reason(), List.of(), started);
                return true;
            }
            case Work.Fail fail -> {
                complete(row, OutboxStatus.FAILED, fail.reason(), List.of(), started);
                return true;
            }
            case Work.Run run -> {
                SyncExecutor executor = null;
                Optional<ScimException> error = Optional.empty();
                try {
                    boolean patch = capabilities.get(run.target(), run.client()).patchSupported();
                    executor = new SyncExecutor(run.client(), run.target().config(), patch);
                    run.step().apply(executor);
                } catch (ScimException e) {
                    error = Optional.of(e);
                } catch (RuntimeException e) {
                    LOG.debugf(e, "outbox row %s failed unexpectedly", row.id());
                    error = Optional.of(new ScimException("unexpected error: " + e.getClass().getSimpleName(), e,
                            true));
                }
                finish(row, executor == null ? List.of() : executor.changes(), error, started);
                return error.map(OutboxWorker::targetAnswered).orElse(true);
            }
        }
    }

    static boolean targetAnswered(ScimException e) {
        return !e.isRetryable() || e.response().map(r -> r.status() != 429).orElse(false);
    }

    private void unclaim(List<String> ids, Duration delay) {
        if (ids.isEmpty()) {
            return;
        }
        KeycloakModelUtils.runJobInTransaction(factory, session -> {
            long now = clock.millis();
            new OutboxRepository(em(session)).unclaim(ids, now + delay.toMillis(), now);
        });
    }

    private Work prepare(KeycloakSession session, OutboxRow row) {
        Optional<ScimTarget> found = new TargetRepository(em(session)).get(row.targetId());
        if (found.isEmpty()) {
            return new Work.Skip("target no longer exists");
        }
        ScimTarget target = found.get();
        if (!target.enabled()) {
            return new Work.Skip("target is disabled");
        }
        RealmModel realm = session.realms().getRealm(row.realmId());
        if (realm == null || !realm.getId().equals(target.realmId())) {
            return new Work.Skip("realm no longer exists");
        }
        session.getContext().setRealm(realm);
        SyncLoader loader = new SyncLoader(session, realm, target, new IdMappingRepository(em(session)));
        // a user or group deleted in Keycloak goes regardless of scope; anything that decides by
        // scope waits until a broken scope is fixed
        boolean deletion = row.operation() == Operation.DELETE;
        if (loader.scope().isMisconfigured() && !deletion) {
            return new Work.Fail(loader.scope().problem() + ", then retry the failed rows");
        }
        ScimClient client;
        try {
            client = clients.create(target, SecretResolver.vault(session));
        } catch (InvalidConfigurationException e) {
            return new Work.Fail("target configuration rejected: " + e.getMessage());
        }
        Step step = switch (row.resourceType()) {
            case USER -> switch (row.operation()) {
                case UPSERT -> {
                    UserState state = loader.user(row.resourceId());
                    yield ex -> ex.upsertUser(state);
                }
                case DELETE -> {
                    String remoteId = loader.userRemoteId(row.resourceId());
                    yield ex -> ex.deleteUser(row.resourceId(), remoteId);
                }
                default -> null;
            };
            case GROUP -> switch (row.operation()) {
                case UPSERT -> {
                    GroupState state = loader.group(row.resourceId());
                    yield ex -> ex.upsertGroup(state);
                }
                case DELETE -> {
                    GroupState state = loader.group(row.resourceId());
                    yield ex -> ex.deleteGroup(row.resourceId(), state.remoteId());
                }
                case MEMBER_ADD -> {
                    GroupState group = loader.group(row.resourceId());
                    UserState user = loader.user(row.relatedId());
                    yield ex -> ex.addMember(group, user);
                }
                case MEMBER_REMOVE -> {
                    GroupState group = loader.group(row.resourceId());
                    String userRemoteId = loader.userRemoteId(row.relatedId());
                    yield ex -> ex.removeMember(group, userRemoteId);
                }
                case SYNC_MEMBERS -> {
                    GroupState group = loader.group(row.resourceId());
                    yield ex -> ex.syncMembers(group);
                }
            };
        };
        if (step == null) {
            return new Work.Fail("unsupported operation " + row.operation() + " for " + row.resourceType());
        }
        return new Work.Run(target, client, step);
    }

    private void finish(OutboxRow row, List<MappingChange> changes, Optional<ScimException> error, long started) {
        if (error.isEmpty()) {
            complete(row, OutboxStatus.DONE, null, changes, started);
            return;
        }
        ScimException e = error.get();
        long now = clock.millis();
        if (e.isRetryable()) {
            Duration delay = backoff.next(row.attempts(), e.retryAfter());
            if (now + delay.toMillis() <= row.retryUntil()) {
                retry(row, now + delay.toMillis(), e.getMessage(), changes, started);
                return;
            }
            complete(row, OutboxStatus.FAILED, "gave up after " + row.attempts() + " attempts: " + e.getMessage(),
                    changes, started);
            return;
        }
        complete(row, OutboxStatus.FAILED, e.getMessage(), changes, started);
    }

    private void retry(OutboxRow row, long nextAttemptAt, String error, List<MappingChange> changes, long started) {
        KeycloakModelUtils.runJobInTransaction(factory, session -> {
            applyMappings(session, row, changes);
            new OutboxRepository(em(session)).markRetry(row.id(), nextAttemptAt, error, clock.millis());
        });
        LOG.debugf("outbox row %s (%s %s) will be retried: %s", row.id(), row.operation(), row.resourceType(), error);
        metrics.rowProcessed(row.targetId(), row.operation().name(), "retry", clock.millis() - started);
    }

    private void complete(OutboxRow row, OutboxStatus status, String message, List<MappingChange> changes,
            long started) {
        KeycloakModelUtils.runJobInTransaction(factory, session -> {
            applyMappings(session, row, changes);
            OutboxRepository outbox = new OutboxRepository(em(session));
            long now = clock.millis();
            switch (status) {
                case DONE -> outbox.markDone(row.id(), now);
                case SUPERSEDED -> outbox.markSuperseded(row.id(), message, now);
                default -> outbox.markFailed(row.id(), message, now);
            }
        });
        if (status == OutboxStatus.FAILED) {
            // the reason can quote personal data from the application, so it stays in the database
            LOG.warnf("scim-outbound: %s %s for target %s failed permanently (row %s); the reason is in"
                    + " GET .../scim-outbound/targets/%s/failed", row.operation(), row.resourceType(), row.targetId(),
                    row.id(), row.targetId());
        }
        metrics.rowProcessed(row.targetId(), row.operation().name(), status.name().toLowerCase(java.util.Locale.ROOT),
                clock.millis() - started);
    }

    private void applyMappings(KeycloakSession session, OutboxRow row, List<MappingChange> changes) {
        if (changes.isEmpty()) {
            return;
        }
        IdMappingRepository mappings = new IdMappingRepository(em(session));
        long now = clock.millis();
        for (MappingChange change : changes) {
            if (change.remoteId() == null) {
                mappings.remove(row.targetId(), change.type(), change.localId());
            } else {
                mappings.put(row.realmId(), row.targetId(), change.type(), change.localId(), change.remoteId(), now);
            }
        }
    }

    private void housekeepingIfDue() {
        long now = clock.millis();
        long last = lastHousekeeping.get();
        if (now - last < HOUSEKEEPING_INTERVAL.toMillis() || !lastHousekeeping.compareAndSet(last, now)) {
            return;
        }
        KeycloakModelUtils.runJobInTransaction(factory, session -> {
            OutboxRepository outbox = new OutboxRepository(em(session));
            int done = outbox.purge(EnumSet.of(OutboxStatus.DONE, OutboxStatus.SUPERSEDED),
                    now - config.retention().toMillis());
            int failed = outbox.purge(EnumSet.of(OutboxStatus.FAILED), now - config.failedRetention().toMillis());
            if (done + failed > 0) {
                LOG.debugf("purged %d finished and %d failed outbox rows", done, failed);
            }
        });
    }

    private void refreshQueueMetrics() {
        try {
            KeycloakModelUtils.runJobInTransaction(factory, session -> {
                OutboxRepository outbox = new OutboxRepository(em(session));
                long now = clock.millis();
                long open = outbox.countOpenGlobal();
                long age = outbox.oldestOpenCreatedAtGlobal().map(t -> now - t).orElse(0L);
                metrics.queueSnapshot(open, age);
            });
        } catch (RuntimeException e) {
            LOG.debug("could not refresh queue metrics", e);
        }
    }

    private static EntityManager em(KeycloakSession session) {
        return session.getProvider(JpaConnectionProvider.class).getEntityManager();
    }
}
