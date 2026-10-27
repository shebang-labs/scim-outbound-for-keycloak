/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 Shebang Labs
 */
package io.shebanglabs.scimoutbound.worker;

import io.shebanglabs.scimoutbound.config.DeprovisionMode;
import io.shebanglabs.scimoutbound.config.TargetConfig;
import io.shebanglabs.scimoutbound.http.Outcome;
import io.shebanglabs.scimoutbound.http.ScimClient;
import io.shebanglabs.scimoutbound.http.ScimException;
import io.shebanglabs.scimoutbound.http.ScimResponse;
import io.shebanglabs.scimoutbound.http.UserListing;
import io.shebanglabs.scimoutbound.mapping.UserMapper;
import io.shebanglabs.scimoutbound.outbox.ResourceType;
import io.shebanglabs.scimoutbound.scim.ScimGroup;
import io.shebanglabs.scimoutbound.scim.ScimJson;
import io.shebanglabs.scimoutbound.scim.ScimMember;
import io.shebanglabs.scimoutbound.scim.ScimPatchOp;
import io.shebanglabs.scimoutbound.scim.ScimSchemas;
import io.shebanglabs.scimoutbound.scim.ScimUser;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Applies one outbox operation to a SCIM target (design report section 5.4). It runs outside any
 * database transaction and only talks HTTP; ID map changes are collected in {@link #changes()}
 * and persisted by the caller, also when an operation fails halfway.
 *
 * <p>Every operation is idempotent: creates look up by {@code userName}/{@code displayName}
 * first and turn 409 into a link, updates that hit 404 re-create, deletes treat 404 as success.
 */
public class SyncExecutor {

    /** Most member changes sent in one request; AWS IAM Identity Center allows no more. */
    static final int MEMBER_CHUNK = 100;

    private final ScimClient client;
    private final TargetConfig config;
    private final boolean patchSupported;
    private final UserMapper mapper;
    private final List<MappingChange> changes = new ArrayList<>();

    public SyncExecutor(ScimClient client, TargetConfig config, boolean patchSupported) {
        this.client = client;
        this.config = config;
        this.patchSupported = patchSupported;
        this.mapper = new UserMapper(config);
    }

    public List<MappingChange> changes() {
        return List.copyOf(changes);
    }

    // ---- users -------------------------------------------------------------------------------

    /** Creates, links or updates the user; deprovisions it if it left the target's scope. */
    public void upsertUser(UserState user) throws ScimException {
        if (!user.exists()) {
            return; // deleted meanwhile: the DELETE row takes care of it
        }
        if (!user.inScope()) {
            if (user.remoteId() != null) {
                deprovision(user.userId(), user.remoteId(), false);
            }
            return;
        }
        ensureUser(user);
    }

    /** The user was deleted in Keycloak. */
    public void deleteUser(String userId, String remoteId) throws ScimException {
        if (remoteId != null) {
            deprovision(userId, remoteId, true);
        }
    }

    private String ensureUser(UserState user) throws ScimException {
        ScimUser desired = user.desired();
        if (user.remoteId() != null) {
            ScimResponse response = updateUser(user.remoteId(), desired);
            if (response.isSuccess()) {
                return user.remoteId();
            }
            if (response.outcome() != Outcome.NOT_FOUND) {
                throw failure("user update", response);
            }
            changes.add(MappingChange.unlink(ResourceType.USER, user.userId()));
        }
        Optional<ScimUser> existing = client.findUserByUserName(desired.userName());
        if (existing.isPresent()) {
            return linkAndUpdate(user, claimableUser(existing.get(), user.userId()));
        }
        ScimResponse created = client.createUser(desired);
        if (created.isSuccess()) {
            String id = idFrom(created, ScimUser.class).map(ScimUser::id)
                    .or(() -> lookupUserId(desired.userName()))
                    .orElseThrow(() -> permanent("user created but the server returned no id"));
            link(ResourceType.USER, user.userId(), id);
            afterUserLinked(user, id);
            return id;
        }
        if (created.outcome() == Outcome.CONFLICT) {
            ScimUser found = client.findUserByUserName(desired.userName())
                    .orElseThrow(() -> permanent("user create failed: " + created.errorSummary() + "; no account"
                            + " with this userName exists, so another account already holds a value that must be"
                            + " unique on the target, usually the email address"));
            return linkAndUpdate(user, claimableUser(found, user.userId()));
        }
        throw failure("user create", created);
    }

    private String linkAndUpdate(UserState user, String remoteId) throws ScimException {
        link(ResourceType.USER, user.userId(), remoteId);
        ScimResponse response = updateUser(remoteId, user.desired());
        if (!response.isSuccess()) {
            throw failure("user update after link", response);
        }
        afterUserLinked(user, remoteId);
        return remoteId;
    }

    /** A user that becomes known to the target is added to the synced groups it belongs to. */
    private void afterUserLinked(UserState user, String remoteId) throws ScimException {
        // PUT-only servers would need a full member list per group: reconciliation repairs those
        if (!config.syncGroupsOrDefault() || !patchSupported) {
            return;
        }
        for (String groupRemoteId : user.groupRemoteIds()) {
            ScimResponse response = client.patchGroup(groupRemoteId,
                    ScimPatchOp.builder().add("members", List.of(ScimMember.of(remoteId))).build());
            if (!response.isSuccess() && response.outcome() != Outcome.NOT_FOUND) {
                throw failure("group membership", response);
            }
        }
    }

    private ScimResponse updateUser(String remoteId, ScimUser desired) throws ScimException {
        return patchSupported
                ? client.patchUser(remoteId, mapper.toReplacePatch(desired))
                : client.replaceUser(remoteId, desired);
    }

    private void deprovision(String userId, String remoteId, boolean userDeleted) throws ScimException {
        ScimResponse response;
        if (config.deprovisionOrDefault() == DeprovisionMode.DELETE) {
            response = client.deleteUser(remoteId);
        } else if (patchSupported) {
            response = client.patchUser(remoteId, ScimPatchOp.builder().replace("active", false).build());
        } else {
            response = deactivateWithPut(remoteId);
        }
        if (!response.isSuccess() && response.outcome() != Outcome.NOT_FOUND) {
            throw failure("user deprovision", response);
        }
        boolean gone = config.deprovisionOrDefault() == DeprovisionMode.DELETE
                || response.outcome() == Outcome.NOT_FOUND;
        // keep the link of a deactivated user that is merely out of scope, so it can be reactivated
        if (userDeleted || gone) {
            changes.add(MappingChange.unlink(ResourceType.USER, userId));
        }
    }

    private ScimResponse deactivateWithPut(String remoteId) throws ScimException {
        ScimResponse current = client.getUser(remoteId);
        if (!current.isSuccess()) {
            return current;
        }
        ScimUser remote = idFrom(current, ScimUser.class)
                .orElseThrow(() -> permanent("could not read user to deactivate it"));
        ScimUser inactive = new ScimUser(remote.schemas() == null ? List.of(ScimSchemas.USER) : remote.schemas(),
                remoteId, remote.externalId(), remote.userName(), remote.name(), remote.displayName(),
                remote.title(), remote.preferredLanguage(), remote.locale(), remote.emails(), false,
                remote.enterprise());
        return client.replaceUser(remoteId, inactive);
    }

    // ---- groups ------------------------------------------------------------------------------

    public void upsertGroup(GroupState group) throws ScimException {
        if (!config.syncGroupsOrDefault() || !group.exists()) {
            return;
        }
        if (!group.inScope()) {
            if (group.remoteId() != null) {
                deleteGroup(group.groupId(), group.remoteId());
            }
            return;
        }
        ensureGroup(group);
    }

    public void deleteGroup(String groupId, String remoteId) throws ScimException {
        if (remoteId == null) {
            return;
        }
        ScimResponse response = client.deleteGroup(remoteId);
        if (!response.isSuccess() && response.outcome() != Outcome.NOT_FOUND) {
            throw failure("group delete", response);
        }
        changes.add(MappingChange.unlink(ResourceType.GROUP, groupId));
    }

    private String ensureGroup(GroupState group) throws ScimException {
        if (group.remoteId() != null) {
            ScimResponse response = patchSupported
                    ? client.patchGroup(group.remoteId(),
                            ScimPatchOp.builder().replace("displayName", group.displayName()).build())
                    : client.replaceGroup(group.remoteId(), scimGroup(group, group.memberRemoteIds()));
            if (response.isSuccess()) {
                return group.remoteId();
            }
            if (response.outcome() != Outcome.NOT_FOUND) {
                throw failure("group update", response);
            }
            changes.add(MappingChange.unlink(ResourceType.GROUP, group.groupId()));
        }
        Optional<ScimGroup> existing = client.findGroupByDisplayName(group.displayName());
        if (existing.isPresent()) {
            String id = claimableGroup(existing.get(), group.groupId());
            link(ResourceType.GROUP, group.groupId(), id);
            replaceMembers(id, group, group.memberRemoteIds());
            return id;
        }
        // PUT-only servers get every member at once; PATCH servers get the rest in chunks
        List<String> members = group.memberRemoteIds();
        int inCreate = patchSupported ? Math.min(members.size(), MEMBER_CHUNK) : members.size();
        ScimResponse created = client.createGroup(scimGroup(group, members.subList(0, inCreate)));
        if (created.isSuccess()) {
            String id = idFrom(created, ScimGroup.class).map(ScimGroup::id)
                    .or(() -> lookupGroupId(group.displayName()))
                    .orElseThrow(() -> permanent("group created but the server returned no id"));
            link(ResourceType.GROUP, group.groupId(), id);
            addMembers(id, members.subList(inCreate, members.size()));
            return id;
        }
        if (created.outcome() == Outcome.CONFLICT) {
            ScimGroup found = client.findGroupByDisplayName(group.displayName())
                    .orElseThrow(() -> permanent("group create failed: " + created.errorSummary() + "; no group"
                            + " with this displayName exists, so another group already holds a value that must be"
                            + " unique on the target"));
            String id = claimableGroup(found, group.groupId());
            link(ResourceType.GROUP, group.groupId(), id);
            replaceMembers(id, group, group.memberRemoteIds());
            return id;
        }
        throw failure("group create", created);
    }

    public void addMember(GroupState group, UserState user) throws ScimException {
        if (!config.syncGroupsOrDefault() || !group.exists() || !group.inScope()
                || !user.exists() || !user.inScope()) {
            return;
        }
        String userRemoteId = user.remoteId() != null ? user.remoteId() : ensureUser(user);
        Set<String> members = new LinkedHashSet<>(group.memberRemoteIds());
        members.add(userRemoteId);
        if (group.remoteId() == null) {
            ensureGroup(new GroupState(group.groupId(), group.displayName(), true, null, List.copyOf(members)));
            return;
        }
        ScimResponse response = patchSupported
                ? client.patchGroup(group.remoteId(),
                        ScimPatchOp.builder().add("members", List.of(ScimMember.of(userRemoteId))).build())
                : client.replaceGroup(group.remoteId(), scimGroup(group, List.copyOf(members)));
        if (response.isSuccess()) {
            return;
        }
        if (response.outcome() == Outcome.NOT_FOUND) {
            changes.add(MappingChange.unlink(ResourceType.GROUP, group.groupId()));
            ensureGroup(new GroupState(group.groupId(), group.displayName(), true, null, List.copyOf(members)));
            return;
        }
        throw failure("group member add", response);
    }

    public void removeMember(GroupState group, String userRemoteId) throws ScimException {
        if (!config.syncGroupsOrDefault() || group.remoteId() == null || userRemoteId == null) {
            return;
        }
        if (patchSupported) {
            removeMembers(group.remoteId(), List.of(userRemoteId), true);
            return;
        }
        List<String> remaining = group.memberRemoteIds().stream().filter(id -> !id.equals(userRemoteId)).toList();
        ScimResponse response = client.replaceGroup(group.remoteId(), scimGroup(group, remaining));
        if (!response.isSuccess() && response.outcome() != Outcome.NOT_FOUND) {
            throw failure("group member remove", response);
        }
    }

    /** Makes the remote member list equal to Keycloak's (used by reconciliation). */
    public void syncMembers(GroupState group) throws ScimException {
        if (!config.syncGroupsOrDefault() || !group.exists() || !group.inScope()) {
            return;
        }
        String id = group.remoteId() != null ? group.remoteId() : ensureGroup(group);
        try {
            replaceMembers(id, group, group.memberRemoteIds());
        } catch (ScimException e) {
            if (e.response().map(r -> r.outcome() == Outcome.NOT_FOUND).orElse(false) && group.remoteId() != null) {
                // removed on the target: create it again with the full member list
                changes.add(MappingChange.unlink(ResourceType.GROUP, group.groupId()));
                ensureGroup(new GroupState(group.groupId(), group.displayName(), true, null,
                        group.memberRemoteIds()));
                return;
            }
            throw e;
        }
    }

    /**
     * Makes the remote member list match. With PATCH only the differences are sent, never a
     * "replace members" or "remove all members", which servers such as AWS IAM Identity Center
     * reject. A server that does not show members on the group (AWS again) gets the additions, and
     * its stray members are found by listing the users that are members of the group, when the
     * server can filter users that way.
     */
    private void replaceMembers(String remoteId, GroupState group, List<String> memberRemoteIds)
            throws ScimException {
        if (!patchSupported) {
            ScimResponse response = client.replaceGroup(remoteId, scimGroup(group, memberRemoteIds));
            if (!response.isSuccess()) {
                throw failure("group member sync", response);
            }
            return;
        }
        ScimResponse current = client.getGroup(remoteId);
        if (!current.isSuccess()) {
            throw failure("group read", current);
        }
        Set<String> wanted = new LinkedHashSet<>(memberRemoteIds);
        Optional<Set<String>> shown = idFrom(current, ScimGroup.class)
                .map(ScimGroup::members)
                .map(list -> list.stream().map(ScimMember::value).filter(Objects::nonNull).collect(Collectors.toSet()));
        if (shown.isEmpty()) {
            addMembers(remoteId, List.copyOf(wanted));
            Optional<Set<String>> listed = membersByUserFilter(remoteId);
            if (listed.isPresent()) {
                removeMembers(remoteId, listed.get().stream().filter(id -> !wanted.contains(id)).toList(), false);
            }
            return;
        }
        addMembers(remoteId, wanted.stream().filter(id -> !shown.get().contains(id)).toList());
        removeMembers(remoteId, shown.get().stream().filter(id -> !wanted.contains(id)).toList(), false);
    }

    /**
     * The members of a group, read as the users matching {@code groups.value eq "<id>"}, or empty
     * when the server cannot tell. A server that ignores the filter would return every user and get
     * them all removed from the group, so the same question is first asked about a group that
     * cannot exist: any answer but "nobody" means the filter is not applied.
     */
    private Optional<Set<String>> membersByUserFilter(String remoteGroupId) throws ScimException {
        try {
            if (client.anyUser(ScimClient.memberOf("no-such-group-" + UUID.randomUUID()))) {
                return Optional.empty();
            }
            return Optional.of(UserListing.all(client, ScimClient.memberOf(remoteGroupId)).keySet());
        } catch (ScimException e) {
            if (e.isRetryable()) {
                throw e;
            }
            return Optional.empty(); // filtering users by group is not supported
        }
    }

    private void addMembers(String remoteId, List<String> memberRemoteIds) throws ScimException {
        for (List<String> chunk : chunks(memberRemoteIds)) {
            ScimResponse response = client.patchGroup(remoteId,
                    ScimPatchOp.builder().add("members", chunk.stream().map(ScimMember::of).toList()).build());
            if (!response.isSuccess()) {
                throw failure("group member add", response);
            }
        }
    }

    /**
     * Removes members by listing them as the value of a {@code members} removal, the form AWS IAM
     * Identity Center and Microsoft Entra use. Servers that only accept the RFC 7644 filter path
     * ({@code members[value eq "id"]}) reject it with 400 and are sent that form instead.
     */
    private void removeMembers(String remoteId, List<String> memberRemoteIds, boolean groupMayBeGone)
            throws ScimException {
        for (List<String> chunk : chunks(memberRemoteIds)) {
            ScimResponse response = client.patchGroup(remoteId,
                    ScimPatchOp.builder().remove("members", chunk.stream().map(ScimMember::of).toList()).build());
            if (response.status() == 400) {
                ScimPatchOp.Builder byFilter = ScimPatchOp.builder();
                chunk.forEach(id -> byFilter.remove("members[value eq " + quote(id) + "]"));
                response = client.patchGroup(remoteId, byFilter.build());
            }
            if (!response.isSuccess() && !(groupMayBeGone && response.outcome() == Outcome.NOT_FOUND)) {
                throw failure("group member remove", response);
            }
        }
    }

    private static List<List<String>> chunks(List<String> ids) {
        List<List<String>> chunks = new ArrayList<>();
        for (int i = 0; i < ids.size(); i += MEMBER_CHUNK) {
            chunks.add(ids.subList(i, Math.min(ids.size(), i + MEMBER_CHUNK)));
        }
        return chunks;
    }

    private static ScimGroup scimGroup(GroupState group, List<String> memberRemoteIds) {
        return ScimGroup.create(group.groupId(), group.displayName(),
                memberRemoteIds.stream().map(ScimMember::of).toList());
    }

    // ---- helpers -----------------------------------------------------------------------------

    /**
     * An account found by name is only taken over when it was provisioned for this Keycloak user
     * (its externalId says so, e.g. after a crash before the link was saved) or the admin allowed
     * taking over existing accounts. Otherwise a test user named like a real one would be linked
     * to the real account, overwritten, and later deactivated or deleted with the test user.
     */
    private String claimableUser(ScimUser remote, String userId) throws ScimException {
        if (userId.equals(remote.externalId()) || config.linkExistingAccountsOrDefault()) {
            return remote.id();
        }
        throw permanent("an account with the same userName already exists on the target but was not"
                + " provisioned for this Keycloak user, so it was left untouched; rename or remove it there,"
                + " or set linkExistingAccounts on the target to take it over");
    }

    private String claimableGroup(ScimGroup remote, String groupId) throws ScimException {
        if (groupId.equals(remote.externalId()) || config.linkExistingAccountsOrDefault()) {
            return remote.id();
        }
        throw permanent("a group with the same displayName already exists on the target but was not"
                + " provisioned for this Keycloak group, so it was left untouched; rename or remove it there,"
                + " or set linkExistingAccounts on the target to take it over");
    }

    private void link(ResourceType type, String localId, String remoteId) {
        changes.add(MappingChange.link(type, localId, remoteId));
    }

    private Optional<String> lookupUserId(String userName) {
        try {
            return client.findUserByUserName(userName).map(ScimUser::id);
        } catch (ScimException e) {
            return Optional.empty();
        }
    }

    private Optional<String> lookupGroupId(String displayName) {
        try {
            return client.findGroupByDisplayName(displayName).map(ScimGroup::id);
        } catch (ScimException e) {
            return Optional.empty();
        }
    }

    private static <T> Optional<T> idFrom(ScimResponse response, Class<T> type) {
        if (response.body() == null || response.body().isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.ofNullable(ScimJson.read(response.body(), type));
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    private static ScimException failure(String action, ScimResponse response) {
        return new ScimException(action + " failed: " + response.errorSummary(), response);
    }

    private static ScimException permanent(String message) {
        return new ScimException(message, null, false);
    }

    private static String quote(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
