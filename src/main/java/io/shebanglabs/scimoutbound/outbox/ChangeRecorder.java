/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 Shebang Labs
 */
package io.shebanglabs.scimoutbound.outbox;

import io.shebanglabs.scimoutbound.config.ScimTarget;
import io.shebanglabs.scimoutbound.config.ServerConfig;
import io.shebanglabs.scimoutbound.jpa.IdMappingRepository;
import io.shebanglabs.scimoutbound.jpa.OutboxRepository;
import io.shebanglabs.scimoutbound.jpa.TargetRepository;
import java.time.Clock;
import java.util.List;
import java.util.function.Predicate;
import org.jboss.logging.Logger;
import org.keycloak.Token;
import org.keycloak.models.ClientModel;
import org.keycloak.models.KeycloakContext;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.models.UserModel;
import org.keycloak.representations.JsonWebToken;

/**
 * Turns Keycloak changes into outbox rows, one per interested target, inside the caller's
 * transaction. It performs no network I/O. Rows hold ids only.
 */
public class ChangeRecorder {

    private static final Logger LOG = Logger.getLogger(ChangeRecorder.class);

    /** Removing access is the most security-relevant change, so it is sent first. */
    static final int PRIORITY_DEPROVISION = 0;
    static final int PRIORITY_NORMAL = 5;
    static final int PRIORITY_BULK = 9;

    private final KeycloakSession session;
    private final ServerConfig config;
    private final Clock clock;
    private final TargetRepository targets;
    private final OutboxRepository outbox;
    private final IdMappingRepository mappings;

    public ChangeRecorder(KeycloakSession session, ServerConfig config, Clock clock, TargetRepository targets,
            OutboxRepository outbox, IdMappingRepository mappings) {
        this.session = session;
        this.config = config;
        this.clock = clock;
        this.targets = targets;
        this.outbox = outbox;
        this.mappings = mappings;
    }

    public void userChanged(RealmModel realm, String userId) {
        UserModel user = session.users().getUserById(realm, userId);
        int priority = user != null && !user.isEnabled() ? PRIORITY_DEPROVISION : PRIORITY_NORMAL;
        record(realm, t -> true, ResourceType.USER, userId, null, Operation.UPSERT, priority);
    }

    public void userDeleted(RealmModel realm, String userId) {
        record(realm, t -> true, ResourceType.USER, userId, null, Operation.DELETE, PRIORITY_DEPROVISION);
    }

    public void groupChanged(RealmModel realm, String groupId) {
        record(realm, t -> t.config().syncGroupsOrDefault(), ResourceType.GROUP, groupId, null, Operation.UPSERT,
                PRIORITY_NORMAL);
    }

    public void groupDeleted(RealmModel realm, String groupId) {
        record(realm, t -> t.config().syncGroupsOrDefault(), ResourceType.GROUP, groupId, null, Operation.DELETE,
                PRIORITY_NORMAL);
    }

    public void memberJoined(RealmModel realm, String groupId, String userId) {
        membershipChanged(realm, groupId, userId, Operation.MEMBER_ADD);
    }

    public void memberLeft(RealmModel realm, String groupId, String userId) {
        membershipChanged(realm, groupId, userId, Operation.MEMBER_REMOVE);
    }

    private void membershipChanged(RealmModel realm, String groupId, String userId, Operation op) {
        record(realm, t -> t.config().syncGroupsOrDefault(), ResourceType.GROUP, groupId, userId, op,
                op == Operation.MEMBER_REMOVE ? PRIORITY_DEPROVISION : PRIORITY_NORMAL);
        // joining or leaving a scope group can move the user in or out of a target's scope
        record(realm, t -> t.config().scope() != null && t.config().scope().isRestricted(), ResourceType.USER,
                userId, null, Operation.UPSERT, PRIORITY_NORMAL);
    }

    /** Queues a row for one specific target, bypassing loop protection (used by reconciliation). */
    public void recordForTarget(ScimTarget target, ResourceType type, String resourceId, Operation op) {
        long now = clock.millis();
        int priority = op == Operation.DELETE ? PRIORITY_DEPROVISION : PRIORITY_BULK;
        outbox.enqueue(new OutboxRepository.NewRow(target.realmId(), target.id(), type, resourceId, null, op,
                priority), now, now + config.maxRetryAge().toMillis());
    }

    /** Removes all plugin data of a deleted realm. */
    public void realmRemoved(String realmId) {
        int rows = outbox.deleteByRealm(realmId);
        int maps = mappings.deleteByRealm(realmId);
        int deleted = targets.deleteByRealm(realmId);
        LOG.debugf("realm removed: deleted %d targets, %d outbox rows, %d id mappings", deleted, rows, maps);
    }

    private void record(RealmModel realm, Predicate<ScimTarget> interested, ResourceType type, String resourceId,
            String relatedId, Operation op, int priority) {
        List<ScimTarget> enabled = targets.listEnabledByRealm(realm.getId());
        if (enabled.isEmpty()) {
            return;
        }
        String origin = originClientId(session);
        long now = clock.millis();
        long retryUntil = now + config.maxRetryAge().toMillis();
        for (ScimTarget target : enabled) {
            if (!interested.test(target)) {
                continue;
            }
            if (origin != null && target.config().ignoredClientIds().contains(origin)) {
                LOG.debugf("skipping %s %s for target %s: change came from ignored client", op, type, target.id());
                continue;
            }
            outbox.enqueue(new OutboxRepository.NewRow(realm.getId(), target.id(), type, resourceId, relatedId, op,
                    priority), now, retryUntil);
        }
    }

    /**
     * The OAuth client that made the current request, if known: the {@code azp} of the bearer token
     * (admin REST, Keycloak's SCIM API) or the session's client.
     */
    static String originClientId(KeycloakSession session) {
        KeycloakContext context = session.getContext();
        if (context == null) {
            return null;
        }
        Token token = context.getBearerToken();
        if (token instanceof JsonWebToken jwt && jwt.getIssuedFor() != null) {
            return jwt.getIssuedFor();
        }
        ClientModel client = context.getClient();
        return client == null ? null : client.getClientId();
    }
}
