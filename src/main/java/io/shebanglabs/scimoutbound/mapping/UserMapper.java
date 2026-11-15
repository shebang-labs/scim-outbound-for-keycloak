/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 Shebang Labs
 */
package io.shebanglabs.scimoutbound.mapping;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.shebanglabs.scimoutbound.config.TargetConfig;
import io.shebanglabs.scimoutbound.scim.ScimEmail;
import io.shebanglabs.scimoutbound.scim.ScimEnterpriseUser;
import io.shebanglabs.scimoutbound.scim.ScimJson;
import io.shebanglabs.scimoutbound.scim.ScimName;
import io.shebanglabs.scimoutbound.scim.ScimPatchOp;
import io.shebanglabs.scimoutbound.scim.ScimSchemas;
import io.shebanglabs.scimoutbound.scim.ScimUser;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.keycloak.models.UserModel;

/**
 * Converts a Keycloak user into a SCIM User using an allowlist: the core attributes below plus
 * whatever the target maps in {@code customAttributes}. Credentials are never read.
 *
 * <table>
 *   <caption>Default mapping</caption>
 *   <tr><td>user id</td><td>externalId</td></tr>
 *   <tr><td>username</td><td>userName</td></tr>
 *   <tr><td>firstName / lastName</td><td>name.givenName / name.familyName</td></tr>
 *   <tr><td>firstName + lastName (or username)</td><td>displayName</td></tr>
 *   <tr><td>email</td><td>emails[type=work, primary=true]</td></tr>
 *   <tr><td>enabled</td><td>active</td></tr>
 * </table>
 */
public class UserMapper {

    private final Map<String, String> custom;

    public UserMapper(TargetConfig config) {
        this.custom = config.customAttributesOrEmpty();
    }

    public ScimUser toScim(UserModel user) {
        return toScim(user, user.isEnabled());
    }

    /** Same as {@link #toScim(UserModel)} but with an explicit {@code active} value. */
    public ScimUser toScim(UserModel user, boolean active) {
        ScimEnterpriseUser enterprise = new ScimEnterpriseUser(
                attr(user, "enterprise.employeeNumber"),
                attr(user, "enterprise.costCenter"),
                attr(user, "enterprise.organization"),
                attr(user, "enterprise.division"),
                attr(user, "enterprise.department"));
        List<String> schemas = enterprise.isEmpty()
                ? List.of(ScimSchemas.USER)
                : List.of(ScimSchemas.USER, ScimSchemas.ENTERPRISE_USER);
        String email = blankToNull(user.getEmail());
        return new ScimUser(
                schemas,
                null,
                user.getId(),
                user.getUsername(),
                ScimName.of(blankToNull(user.getFirstName()), blankToNull(user.getLastName())),
                displayName(user),
                attr(user, "title"),
                attr(user, "preferredLanguage"),
                attr(user, "locale"),
                email == null ? null : List.of(ScimEmail.work(email)),
                active,
                enterprise.isEmpty() ? null : enterprise);
    }

    /**
     * Builds a PATCH that sets every mapped attribute to its current value. Using {@code replace}
     * for each attribute keeps the request idempotent and compatible with servers that do not
     * support value-filtered paths.
     */
    public ScimPatchOp toReplacePatch(ScimUser desired) {
        ScimPatchOp.Builder patch = ScimPatchOp.builder()
                .replace("userName", desired.userName())
                .replace("externalId", desired.externalId())
                .replace("active", desired.active());
        replaceOrRemove(patch, "displayName", desired.displayName());
        if (desired.name() != null) {
            patch.replace("name", desired.name());
        }
        if (desired.emails() != null) {
            patch.replace("emails", desired.emails());
        }
        replaceOrRemove(patch, "title", desired.title());
        replaceOrRemove(patch, "preferredLanguage", desired.preferredLanguage());
        replaceOrRemove(patch, "locale", desired.locale());
        if (desired.enterprise() != null) {
            patch.replace(ScimSchemas.ENTERPRISE_USER, desired.enterprise());
        }
        return patch.build();
    }

    /**
     * Merges the mapped attributes into a user as read from the target, for a full PUT. It has the
     * same effect as {@link #toReplacePatch}: attributes we map are set (complex ones sub-attribute
     * by sub-attribute), attributes we own and no longer have are removed, and everything else the
     * target keeps on the user, including attributes this plugin does not model, is sent back
     * unchanged. Read-only attributes ({@code meta}, {@code groups}) are dropped.
     *
     * @param remote the user as returned by {@code GET /Users/{id}}; changed in place
     * @param remoteId the user's id on the target
     * @return {@code remote}
     */
    public ObjectNode mergeForPut(ObjectNode remote, String remoteId, ScimUser desired) {
        remote.remove("meta");
        remote.remove("groups");
        remote.put("id", remoteId);
        ensureSchema(remote, ScimSchemas.USER);
        remote.put("userName", desired.userName());
        remote.put("externalId", desired.externalId());
        remote.put("active", desired.active());
        putOrRemove(remote, "displayName", desired.displayName());
        if (desired.name() != null) {
            mergeObject(remote, "name", desired.name());
        }
        if (desired.emails() != null) {
            remote.set("emails", ScimJson.mapper().valueToTree(desired.emails()));
        }
        putOrRemove(remote, "title", desired.title());
        putOrRemove(remote, "preferredLanguage", desired.preferredLanguage());
        putOrRemove(remote, "locale", desired.locale());
        if (desired.enterprise() != null) {
            ensureSchema(remote, ScimSchemas.ENTERPRISE_USER);
            mergeObject(remote, ScimSchemas.ENTERPRISE_USER, desired.enterprise());
        }
        return remote;
    }

    /** Sets {@code active} on a user as read from the target, for a full PUT; nothing else changes. */
    public static ObjectNode withActive(ObjectNode remote, String remoteId, boolean active) {
        remote.remove("meta");
        remote.remove("groups");
        remote.put("id", remoteId);
        ensureSchema(remote, ScimSchemas.USER);
        remote.put("active", active);
        return remote;
    }

    private void putOrRemove(ObjectNode node, String attribute, String value) {
        if (value != null) {
            node.put(attribute, value);
        } else if (custom.containsKey(attribute) || attribute.equals("displayName")) {
            node.remove(attribute); // only clear attributes we own, never ones the app manages itself
        }
    }

    private static void mergeObject(ObjectNode node, String attribute, Object value) {
        ObjectNode desired = ScimJson.mapper().valueToTree(value);
        JsonNode current = node.get(attribute);
        if (current instanceof ObjectNode existing) {
            existing.setAll(desired);
        } else {
            node.set(attribute, desired);
        }
    }

    private static void ensureSchema(ObjectNode node, String schema) {
        JsonNode schemas = node.get("schemas");
        ArrayNode list = schemas instanceof ArrayNode array ? array : node.putArray("schemas");
        for (JsonNode s : list) {
            if (schema.equals(s.asText())) {
                return;
            }
        }
        list.add(schema);
    }

    private void replaceOrRemove(ScimPatchOp.Builder patch, String path, String value) {
        if (value != null) {
            patch.replace(path, value);
        } else if (custom.containsKey(path) || path.equals("displayName")) {
            // only clear attributes we own, never ones the app manages itself
            patch.remove(path);
        }
    }

    /** Attributes that the reconciler compares to detect drift. */
    public static List<String> comparableDifferences(ScimUser desired, ScimUser remote) {
        List<String> diffs = new ArrayList<>();
        if (!eq(desired.userName(), remote.userName())) {
            diffs.add("userName");
        }
        if (!eq(desired.active(), remote.active() == null ? Boolean.TRUE : remote.active())) {
            diffs.add("active");
        }
        if (remote.emails() != null && !eq(desired.primaryEmail(), remote.primaryEmail())) {
            diffs.add("emails");
        }
        if (remote.displayName() != null && !eq(desired.displayName(), remote.displayName())) {
            diffs.add("displayName");
        }
        if (remote.name() != null && desired.name() != null
                && (!eq(desired.name().givenName(), remote.name().givenName())
                || !eq(desired.name().familyName(), remote.name().familyName()))) {
            diffs.add("name");
        }
        return diffs;
    }

    private static boolean eq(Object a, Object b) {
        return a == null ? b == null : a.equals(b);
    }

    private String attr(UserModel user, String scimAttribute) {
        String kcAttribute = custom.get(scimAttribute);
        return kcAttribute == null ? null : blankToNull(user.getFirstAttribute(kcAttribute));
    }

    static String displayName(UserModel user) {
        String first = blankToNull(user.getFirstName());
        String last = blankToNull(user.getLastName());
        if (first == null && last == null) {
            return user.getUsername();
        }
        if (first == null) {
            return last;
        }
        return last == null ? first : first + " " + last;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}
