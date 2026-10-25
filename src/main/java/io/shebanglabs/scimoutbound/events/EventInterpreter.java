/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 Shebang Labs
 */
package io.shebanglabs.scimoutbound.events;

import io.shebanglabs.scimoutbound.scim.ScimJson;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.keycloak.events.EventType;
import org.keycloak.events.admin.OperationType;
import org.keycloak.events.admin.ResourceType;

/**
 * Maps Keycloak user and admin events to {@link Change}s (design report section 4.1). Pure
 * functions, no Keycloak runtime needed.
 */
public final class EventInterpreter {

    private static final Pattern USER_PATH = Pattern.compile("^users/([^/]+)$");
    private static final Pattern USER_SUBPATH = Pattern.compile("^users/([^/]+)/.+$");
    private static final Pattern MEMBERSHIP_PATH = Pattern.compile("^users/([^/]+)/groups/([^/]+)$");
    private static final Pattern GROUP_PATH = Pattern.compile("^groups/([^/]+)$");
    private static final Pattern CHILD_GROUP_PATH = Pattern.compile("^groups/([^/]+)/children$");

    private EventInterpreter() {
    }

    /** Events emitted by end-user actions (registration, account console, first broker login). */
    public static Optional<Change> fromUserEvent(EventType type, String userId, String error) {
        if (error != null || userId == null || type == null) {
            return Optional.empty();
        }
        return switch (type) {
            case REGISTER, UPDATE_PROFILE, UPDATE_EMAIL, VERIFY_EMAIL -> Optional.of(new Change.UserChanged(userId));
            case DELETE_ACCOUNT -> Optional.of(new Change.UserDeleted(userId));
            default -> Optional.empty();
        };
    }

    /**
     * Events emitted by the admin REST API.
     *
     * @param representation JSON representation if the realm includes it, otherwise {@code null}
     */
    public static Optional<Change> fromAdminEvent(ResourceType resourceType, OperationType operation,
            String resourcePath, String representation, String error) {
        if (error != null || resourceType == null || operation == null || resourcePath == null) {
            return Optional.empty();
        }
        Matcher m;
        switch (resourceType) {
            case USER -> {
                if ((m = USER_PATH.matcher(resourcePath)).matches()) {
                    return Optional.of(operation == OperationType.DELETE
                            ? new Change.UserDeleted(m.group(1))
                            : new Change.UserChanged(m.group(1)));
                }
                // e.g. users/{id}/reset-password, users/{id}/execute-actions-email: profile may change
                if ((m = USER_SUBPATH.matcher(resourcePath)).matches() && operation == OperationType.ACTION) {
                    return Optional.of(new Change.UserChanged(m.group(1)));
                }
            }
            case GROUP_MEMBERSHIP -> {
                if ((m = MEMBERSHIP_PATH.matcher(resourcePath)).matches()) {
                    return Optional.of(operation == OperationType.DELETE
                            ? new Change.MemberLeft(m.group(2), m.group(1))
                            : new Change.MemberJoined(m.group(2), m.group(1)));
                }
            }
            case GROUP -> {
                if ((m = GROUP_PATH.matcher(resourcePath)).matches()) {
                    return Optional.of(operation == OperationType.DELETE
                            ? new Change.GroupDeleted(m.group(1))
                            : new Change.GroupChanged(m.group(1)));
                }
                if (CHILD_GROUP_PATH.matcher(resourcePath).matches() && operation == OperationType.CREATE) {
                    return idFromRepresentation(representation).map(Change.GroupChanged::new);
                }
            }
            default -> {
                return Optional.empty();
            }
        }
        return Optional.empty();
    }

    private static Optional<String> idFromRepresentation(String representation) {
        if (representation == null) {
            return Optional.empty();
        }
        try {
            String id = ScimJson.mapper().readTree(representation).path("id").asText(null);
            return Optional.ofNullable(id);
        } catch (Exception e) {
            return Optional.empty();
        }
    }
}
