/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 Shebang Labs
 */
package io.shebanglabs.scimoutbound.http;

import io.shebanglabs.scimoutbound.scim.ScimListResponse;
import io.shebanglabs.scimoutbound.scim.ScimUser;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reads every remote user, or every user matching a filter, page by page.
 *
 * <p>The first page is requested with an empty cursor (RFC 9865): cursor servers answer with
 * {@code nextCursor}, index-only servers ignore the parameter and answer page one with
 * {@code totalResults}, and strict ones reject it, after which paging falls back to
 * {@code startIndex}. A server that repeats a cursor or a page is reported instead of being asked
 * forever.
 */
public final class UserListing {

    static final int PAGE = 100;
    /** Upper bound on remote users read into memory at once. */
    static final int MAX_USERS = 500_000;

    private UserListing() {
    }

    /**
     * @param filter a SCIM filter, or {@code null} for all users
     * @return the users by remote id
     */
    public static Map<String, ScimUser> all(ScimClient client, String filter) throws ScimException {
        Map<String, ScimUser> byId = new HashMap<>();
        ScimListResponse<ScimUser> first;
        try {
            first = client.listUsersByCursor("", PAGE, filter);
        } catch (ScimException e) {
            if (e.response().map(r -> r.status() == 400).orElse(false)) {
                return byIndex(client, filter, byId, 1);
            }
            throw e;
        }
        add(byId, first);
        if (first.nextCursor() != null && !first.nextCursor().isEmpty()) {
            return byCursor(client, filter, byId, first.nextCursor());
        }
        int read = first.resourcesOrEmpty().size();
        if (read > 0 && first.totalResults() != null && first.totalResults() > read) {
            return byIndex(client, filter, byId, 1 + read);
        }
        return byId;
    }

    private static Map<String, ScimUser> byCursor(ScimClient client, String filter, Map<String, ScimUser> byId,
            String cursor) throws ScimException {
        Set<String> seen = new HashSet<>();
        while (cursor != null && !cursor.isEmpty()) {
            if (!seen.add(cursor)) {
                throw new ScimException("target repeated a pagination cursor; aborting", null, false);
            }
            ScimListResponse<ScimUser> page = client.listUsersByCursor(cursor, PAGE, filter);
            add(byId, page);
            cursor = page.nextCursor();
        }
        return byId;
    }

    private static Map<String, ScimUser> byIndex(ScimClient client, String filter, Map<String, ScimUser> byId,
            int startIndex) throws ScimException {
        while (true) {
            ScimListResponse<ScimUser> page = client.listUsers(startIndex, PAGE, filter);
            List<ScimUser> resources = page.resourcesOrEmpty();
            if (!resources.isEmpty() && add(byId, page) == 0) {
                throw new ScimException("target returned the same users again for startIndex " + startIndex
                        + " (it may not support startIndex); aborting", null, false);
            }
            startIndex += resources.size();
            if (resources.isEmpty() || startIndex > page.total()) {
                return byId;
            }
        }
    }

    /** Adds a page and returns how many users were new. */
    private static int add(Map<String, ScimUser> byId, ScimListResponse<ScimUser> page) throws ScimException {
        int added = 0;
        for (ScimUser user : page.resourcesOrEmpty()) {
            if (user.id() != null && byId.put(user.id(), user) == null) {
                added++;
            }
        }
        if (byId.size() > MAX_USERS) {
            throw new ScimException("target has more than " + MAX_USERS + " users; aborting", null, false);
        }
        return added;
    }
}
