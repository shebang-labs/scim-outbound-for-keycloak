/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 Shebang Labs
 */
package io.shebanglabs.scimoutbound.reconcile;

import io.shebanglabs.scimoutbound.auth.SecretResolver;
import io.shebanglabs.scimoutbound.config.InvalidConfigurationException;
import io.shebanglabs.scimoutbound.config.ScimTarget;
import io.shebanglabs.scimoutbound.config.ServerConfig;
import io.shebanglabs.scimoutbound.http.ScimClient;
import io.shebanglabs.scimoutbound.http.ScimException;
import io.shebanglabs.scimoutbound.http.UserListing;
import io.shebanglabs.scimoutbound.jpa.IdMappingEntity;
import io.shebanglabs.scimoutbound.jpa.IdMappingRepository;
import io.shebanglabs.scimoutbound.jpa.OutboxRepository;
import io.shebanglabs.scimoutbound.jpa.TargetRepository;
import io.shebanglabs.scimoutbound.mapping.UserMapper;
import io.shebanglabs.scimoutbound.metrics.ScimMetrics;
import io.shebanglabs.scimoutbound.outbox.ChangeRecorder;
import io.shebanglabs.scimoutbound.outbox.Operation;
import io.shebanglabs.scimoutbound.outbox.ResourceType;
import io.shebanglabs.scimoutbound.reconcile.ReconcilePlanner.LocalUser;
import io.shebanglabs.scimoutbound.reconcile.ReconcilePlanner.OrphanMapping;
import io.shebanglabs.scimoutbound.reconcile.ReconcilePlanner.UserPlan;
import io.shebanglabs.scimoutbound.scim.ScimJson;
import io.shebanglabs.scimoutbound.scim.ScimUser;
import io.shebanglabs.scimoutbound.worker.ClientFactory;
import io.shebanglabs.scimoutbound.worker.ScopeEvaluator;
import jakarta.persistence.EntityManager;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.jboss.logging.Logger;
import org.keycloak.connections.jpa.JpaConnectionProvider;
import org.keycloak.models.GroupModel;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;
import org.keycloak.models.RealmModel;
import org.keycloak.models.UserModel;
import org.keycloak.models.utils.KeycloakModelUtils;

/**
 * Full comparison of Keycloak and one target (design report section 8.3). It is also how
 * existing users reach a newly connected target. All fixes go through the outbox, so they share
 * retries, ordering and monitoring with live events.
 *
 * <p>Network reads (listing remote users) run outside transactions; Keycloak data is read in
 * pages, each in its own short transaction.
 */
public class Reconciler {

    private static final Logger LOG = Logger.getLogger(Reconciler.class);
    static final int LOCAL_PAGE = 500;
    /** Upper bound on Keycloak users held in memory by one run, so a huge realm cannot exhaust the heap. */
    static final int MAX_LOCAL_USERS = 500_000;

    private final KeycloakSessionFactory factory;
    private final ServerConfig serverConfig;
    private final Clock clock;
    private final ClientFactory clients;
    private final ScimMetrics metrics;

    public Reconciler(KeycloakSessionFactory factory, ServerConfig serverConfig, Clock clock, ClientFactory clients,
            ScimMetrics metrics) {
        this.factory = factory;
        this.serverConfig = serverConfig;
        this.clock = clock;
        this.clients = clients;
        this.metrics = metrics;
    }

    /** Runs scheduled reconciliation for every enabled target. */
    public void runScheduled() {
        List<String> targetIds = KeycloakModelUtils.runJobInTransactionWithResult(factory,
                s -> new TargetRepository(em(s)).listAllEnabled().stream().map(ScimTarget::id).toList());
        for (String targetId : targetIds) {
            run(targetId, true, false);
        }
    }

    /**
     * @param dryRun only report, queue nothing
     * @param manual started by an admin; a successful manual non-dry run arms scheduled runs
     */
    public ReconcileReport run(String targetId, boolean dryRun, boolean manual) {
        long started = clock.millis();
        ReconcileReport report;
        try {
            report = doRun(targetId, dryRun, manual, started);
        } catch (ScimException | InvalidConfigurationException e) {
            report = ReconcileReport.failed(targetId, dryRun, started, clock.millis(), "failed: " + e.getMessage());
        } catch (RuntimeException e) {
            LOG.warnf(e, "scim-outbound: reconciliation of target %s failed unexpectedly", targetId);
            report = ReconcileReport.failed(targetId, dryRun, started, clock.millis(),
                    "failed: " + e.getClass().getSimpleName());
        }
        ReconcileReport finalReport = report;
        boolean arm = manual && !finalReport.dryRun() && !finalReport.blocked() && finalReport.message() == null;
        KeycloakModelUtils.runJobInTransaction(factory, s -> new TargetRepository(em(s))
                .markReconciled(targetId, arm, ScimJson.write(finalReport), clock.millis()));
        if (finalReport.blocked()) {
            LOG.warnf("scim-outbound: reconciliation of target %s blocked by the removal safety limit"
                    + " (%d removals); nothing was changed", targetId, finalReport.userRemovals());
        }
        metrics.reconciled(targetId, finalReport.userCreates(), finalReport.userUpdates(), finalReport.userRemovals(),
                finalReport.blocked());
        return finalReport;
    }

    private record Setup(ScimTarget target, ScimClient client, boolean effectiveDryRun) {
    }

    private ReconcileReport doRun(String targetId, boolean requestedDryRun, boolean manual, long started)
            throws ScimException {
        Optional<Setup> setup = KeycloakModelUtils.runJobInTransactionWithResult(factory, s -> {
            Optional<ScimTarget> target = new TargetRepository(em(s)).get(targetId);
            if (target.isEmpty() || !target.get().enabled()) {
                return Optional.empty();
            }
            bindRealm(s, target.get().realmId());
            ScimClient client = clients.create(target.get(), SecretResolver.vault(s));
            // until an admin has run one real reconciliation, scheduled runs only report
            boolean dry = requestedDryRun
                    || (!manual && !target.get().reconcileArmed() && target.get().config().firstReconcileDryRun());
            return Optional.of(new Setup(target.get(), client, dry));
        });
        if (setup.isEmpty()) {
            return ReconcileReport.failed(targetId, requestedDryRun, started, clock.millis(),
                    "target does not exist or is disabled");
        }
        ScimTarget target = setup.get().target();
        boolean dryRun = setup.get().effectiveDryRun();

        Map<String, ScimUser> remote = UserListing.all(setup.get().client(), null);
        LocalSide local = readLocalSide(target);
        if (local.scopeMisconfigured()) {
            return ReconcileReport.failed(targetId, dryRun, started, clock.millis(),
                    "a scope group does not exist (renamed, moved or deleted?): fix scope.groups;"
                            + " nothing was changed");
        }
        UserPlan plan = ReconcilePlanner.planUsers(local.users(), remote, local.orphans());
        int removals = plan.removals() + local.orphanGroups().size();
        boolean blocked = ReconcilePlanner.removalsBlocked(removals, local.managedUsers(),
                target.config().maxRemovalPercent(), target.config().removalFloor());

        if (!dryRun && !blocked) {
            KeycloakModelUtils.runJobInTransaction(factory, s -> {
                bindRealm(s, target.realmId());
                ChangeRecorder recorder = recorder(s);
                plan.upserts().forEach(id -> recorder.recordForTarget(target, ResourceType.USER, id, Operation.UPSERT));
                plan.deprovisionOutOfScope().forEach(id ->
                        recorder.recordForTarget(target, ResourceType.USER, id, Operation.UPSERT));
                plan.deleteOrphans().forEach(id ->
                        recorder.recordForTarget(target, ResourceType.USER, id, Operation.DELETE));
                for (String groupId : local.groups()) {
                    recorder.recordForTarget(target, ResourceType.GROUP, groupId, Operation.UPSERT);
                    recorder.recordForTarget(target, ResourceType.GROUP, groupId, Operation.SYNC_MEMBERS);
                }
                local.orphanGroups().forEach(id ->
                        recorder.recordForTarget(target, ResourceType.GROUP, id, Operation.DELETE));
            });
        }
        String message = blocked ? "blocked: " + removals + " removals exceed the safety limit of "
                + target.config().maxRemovalPercent() + "% (floor " + target.config().removalFloor()
                + "); run a dry run, review, then adjust safety settings for one run" : null;
        return new ReconcileReport(targetId, dryRun, blocked, local.users().size(), remote.size(), plan.creates(),
                plan.updates(), removals - local.orphanGroups().size(), plan.unmanagedRemote(), local.groups().size(),
                local.orphanGroups().size(), started, clock.millis(), message);
    }

    private record LocalSide(List<LocalUser> users, List<OrphanMapping> orphans, long managedUsers,
            List<String> groups, List<String> orphanGroups, boolean scopeMisconfigured) {
    }

    private LocalSide readLocalSide(ScimTarget target) throws ScimException {
        List<LocalUser> users = new ArrayList<>();
        boolean[] misconfigured = {false};
        // users, one page per transaction
        for (int first = 0; ; first += LOCAL_PAGE) {
            int offset = first;
            List<LocalUser> page = KeycloakModelUtils.runJobInTransactionWithResult(factory, s -> {
                RealmModel realm = bindRealm(s, target.realmId());
                ScopeEvaluator scope = new ScopeEvaluator(s, realm, target.config());
                misconfigured[0] = scope.isMisconfigured();
                UserMapper mapper = new UserMapper(target.config());
                List<UserModel> models = s.users().searchForUserStream(realm, Map.of(), offset, LOCAL_PAGE).toList();
                Map<String, String> remoteIds = new IdMappingRepository(em(s)).remoteIds(target.id(), ResourceType.USER,
                        models.stream().map(UserModel::getId).toList());
                return models.stream()
                        .map(u -> new LocalUser(u.getId(), scope.includes(u), mapper.toScim(u), remoteIds.get(u.getId())))
                        .toList();
            });
            users.addAll(page);
            if (users.size() > MAX_LOCAL_USERS) {
                throw new ScimException("realm has more than " + MAX_LOCAL_USERS + " users; aborting", null, false);
            }
            if (page.size() < LOCAL_PAGE || misconfigured[0]) {
                break;
            }
        }
        Set<String> localIds = users.stream().map(LocalUser::id).collect(Collectors.toSet());
        // mappings whose Keycloak user is gone, and the number of managed accounts
        List<OrphanMapping> orphans = new ArrayList<>();
        long managed = 0;
        for (int first = 0; ; first += LOCAL_PAGE) {
            int offset = first;
            List<IdMappingEntity> page = KeycloakModelUtils.runJobInTransactionWithResult(factory,
                    s -> new IdMappingRepository(em(s)).page(target.id(), ResourceType.USER, offset, LOCAL_PAGE));
            managed += page.size();
            page.stream().filter(m -> !localIds.contains(m.getLocalId()))
                    .forEach(m -> orphans.add(new OrphanMapping(m.getLocalId(), m.getRemoteId())));
            if (page.size() < LOCAL_PAGE) {
                break;
            }
        }
        List<String> groups = new ArrayList<>();
        List<String> orphanGroups = new ArrayList<>();
        if (target.config().syncGroupsOrDefault()) {
            KeycloakModelUtils.runJobInTransaction(factory, s -> {
                RealmModel realm = bindRealm(s, target.realmId());
                ScopeEvaluator scope = new ScopeEvaluator(s, realm, target.config());
                Set<String> existing = s.groups().getGroupsStream(realm).map(GroupModel::getId)
                        .collect(Collectors.toSet());
                s.groups().getGroupsStream(realm).filter(scope::includes).map(GroupModel::getId).forEach(groups::add);
                IdMappingRepository mappings = new IdMappingRepository(em(s));
                long count = mappings.count(target.id(), ResourceType.GROUP);
                for (int first = 0; first < count; first += LOCAL_PAGE) {
                    mappings.page(target.id(), ResourceType.GROUP, first, LOCAL_PAGE).stream()
                            .filter(m -> !existing.contains(m.getLocalId()))
                            .forEach(m -> orphanGroups.add(m.getLocalId()));
                }
            });
        }
        return new LocalSide(users, orphans, managed, groups, orphanGroups, misconfigured[0]);
    }

    /** Background sessions must be bound to the realm before user lookups (Keycloak 26). */
    private static RealmModel bindRealm(KeycloakSession s, String realmId) {
        RealmModel realm = s.realms().getRealm(realmId);
        s.getContext().setRealm(realm);
        return realm;
    }

    private ChangeRecorder recorder(KeycloakSession s) {
        EntityManager em = em(s);
        return new ChangeRecorder(s, serverConfig, clock, new TargetRepository(em), new OutboxRepository(em),
                new IdMappingRepository(em));
    }

    private static EntityManager em(KeycloakSession session) {
        return session.getProvider(JpaConnectionProvider.class).getEntityManager();
    }
}
