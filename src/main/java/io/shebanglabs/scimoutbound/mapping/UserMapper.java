/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 Shebang Labs
 */
package io.shebanglabs.scimoutbound.mapping;

import io.shebanglabs.scimoutbound.config.TargetConfig;
import io.shebanglabs.scimoutbound.scim.ScimEmail;
import io.shebanglabs.scimoutbound.scim.ScimEnterpriseUser;
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
