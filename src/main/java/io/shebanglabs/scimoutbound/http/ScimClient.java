/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 Shebang Labs
 */
package io.shebanglabs.scimoutbound.http;

import com.fasterxml.jackson.core.type.TypeReference;
import io.shebanglabs.scimoutbound.auth.AuthenticationException;
import io.shebanglabs.scimoutbound.auth.Authenticator;
import io.shebanglabs.scimoutbound.scim.ScimGroup;
import io.shebanglabs.scimoutbound.scim.ScimJson;
import io.shebanglabs.scimoutbound.scim.ScimListResponse;
import io.shebanglabs.scimoutbound.scim.ScimPatchOp;
import io.shebanglabs.scimoutbound.scim.ScimServiceProviderConfig;
import io.shebanglabs.scimoutbound.scim.ScimUser;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;

/**
 * SCIM 2.0 protocol client (RFC 7644) for one target. Every call returns the raw
 * {@link ScimResponse} so callers decide how to react to each {@link Outcome}. A 401 triggers
 * exactly one credential refresh and retry when the authenticator supports it.
 */
public class ScimClient {

    private static final TypeReference<ScimListResponse<ScimUser>> USER_LIST = new TypeReference<>() { };
    private static final TypeReference<ScimListResponse<ScimGroup>> GROUP_LIST = new TypeReference<>() { };

    private final ScimHttp http;
    private final String baseUrl;
    private final Authenticator authenticator;

    public ScimClient(ScimHttp http, URI baseUrl, Authenticator authenticator) {
        this.http = http;
        String base = baseUrl.toString();
        this.baseUrl = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
        this.authenticator = authenticator;
    }

    // ---- discovery ---------------------------------------------------------------------------

    public ScimServiceProviderConfig serviceProviderConfig() throws ScimException {
        ScimResponse response = call("GET", "/ServiceProviderConfig", null);
        if (!response.isSuccess()) {
            return ScimServiceProviderConfig.ASSUMED;
        }
        try {
            return response.as(ScimServiceProviderConfig.class);
        } catch (IOException e) {
            return ScimServiceProviderConfig.ASSUMED;
        }
    }

    /** Raw call used by "test connection". */
    public ScimResponse probe() throws ScimException {
        return call("GET", "/ServiceProviderConfig", null);
    }

    // ---- users -------------------------------------------------------------------------------

    public Optional<ScimUser> findUserByUserName(String userName) throws ScimException {
        return findOne("/Users", "userName eq " + quote(userName), USER_LIST);
    }

    public ScimResponse createUser(ScimUser user) throws ScimException {
        return call("POST", "/Users", ScimJson.write(user));
    }

    public ScimResponse getUser(String id) throws ScimException {
        return call("GET", "/Users/" + encodePath(id), null);
    }

    public ScimResponse replaceUser(String id, ScimUser user) throws ScimException {
        return call("PUT", "/Users/" + encodePath(id), ScimJson.write(user.withId(id)));
    }

    public ScimResponse patchUser(String id, ScimPatchOp patch) throws ScimException {
        return call("PATCH", "/Users/" + encodePath(id), ScimJson.write(patch));
    }

    public ScimResponse deleteUser(String id) throws ScimException {
        return call("DELETE", "/Users/" + encodePath(id), null);
    }

    /**
     * One page of users by index (RFC 7644 section 3.4.2.4).
     *
     * @param filter a SCIM filter, or {@code null} for all users
     */
    public ScimListResponse<ScimUser> listUsers(int startIndex, int count, String filter) throws ScimException {
        return list("/Users?startIndex=" + startIndex + "&count=" + count + filterParam(filter), USER_LIST);
    }

    /**
     * One page of users by cursor (RFC 9865). An empty cursor asks for the first page; servers
     * that page this way (such as AWS IAM Identity Center) do not support {@code startIndex}.
     *
     * @param filter a SCIM filter, or {@code null} for all users
     */
    public ScimListResponse<ScimUser> listUsersByCursor(String cursor, int count, String filter)
            throws ScimException {
        String query = cursor.isEmpty() ? "?cursor" : "?cursor=" + encodeQuery(cursor);
        return list("/Users" + query + "&count=" + count + filterParam(filter), USER_LIST);
    }

    /** Whether any user matches a filter; reads a single result. */
    public boolean anyUser(String filter) throws ScimException {
        return !list("/Users?filter=" + encodeQuery(filter) + "&count=1", USER_LIST).resourcesOrEmpty().isEmpty();
    }

    /** Filter matching the users that are members of a remote group. */
    public static String memberOf(String remoteGroupId) {
        return "groups.value eq " + quote(remoteGroupId);
    }

    private static String filterParam(String filter) {
        return filter == null ? "" : "&filter=" + encodeQuery(filter);
    }

    // ---- groups ------------------------------------------------------------------------------

    public Optional<ScimGroup> findGroupByDisplayName(String displayName) throws ScimException {
        return findOne("/Groups", "displayName eq " + quote(displayName), GROUP_LIST);
    }

    public ScimResponse createGroup(ScimGroup group) throws ScimException {
        return call("POST", "/Groups", ScimJson.write(group));
    }

    public ScimResponse getGroup(String id) throws ScimException {
        return call("GET", "/Groups/" + encodePath(id), null);
    }

    public ScimResponse replaceGroup(String id, ScimGroup group) throws ScimException {
        ScimGroup withId = new ScimGroup(group.schemas(), id, group.externalId(), group.displayName(), group.members());
        return call("PUT", "/Groups/" + encodePath(id), ScimJson.write(withId));
    }

    public ScimResponse patchGroup(String id, ScimPatchOp patch) throws ScimException {
        return call("PATCH", "/Groups/" + encodePath(id), ScimJson.write(patch));
    }

    public ScimResponse deleteGroup(String id) throws ScimException {
        return call("DELETE", "/Groups/" + encodePath(id), null);
    }

    // ---- plumbing ----------------------------------------------------------------------------

    private <T> Optional<T> findOne(String path, String filter, TypeReference<ScimListResponse<T>> type)
            throws ScimException {
        ScimResponse response = call("GET", path + "?filter=" + encodeQuery(filter), null);
        if (!response.isSuccess()) {
            throw new ScimException("lookup failed: " + response.errorSummary(), response);
        }
        try {
            ScimListResponse<T> list = ScimJson.read(response.body(), type);
            return list.resourcesOrEmpty().stream().findFirst();
        } catch (IOException e) {
            throw new ScimException("lookup returned an invalid ListResponse", response);
        }
    }

    private <T> ScimListResponse<T> list(String pathAndQuery, TypeReference<ScimListResponse<T>> type)
            throws ScimException {
        ScimResponse response = call("GET", pathAndQuery, null);
        if (!response.isSuccess()) {
            throw new ScimException("list failed: " + response.errorSummary(), response);
        }
        try {
            return ScimJson.read(response.body(), type);
        } catch (IOException e) {
            throw new ScimException("list returned an invalid ListResponse", response);
        }
    }

    ScimResponse call(String method, String pathAndQuery, String body) throws ScimException {
        URI uri = URI.create(baseUrl + pathAndQuery);
        try {
            ScimResponse response = http.send(method, uri, body,
                    Map.of("Authorization", authenticator.authorization(false)));
            if (response.outcome() == Outcome.UNAUTHORIZED && authenticator.canRefresh()) {
                response = http.send(method, uri, body, Map.of("Authorization", authenticator.authorization(true)));
            }
            return response;
        } catch (ScimTransportException e) {
            throw new ScimException(e.getMessage(), e, !e.isPermanent());
        } catch (AuthenticationException e) {
            throw new ScimException("authentication failed: " + e.getMessage(), e, e.isRetryable());
        }
    }

    /** Quotes a value for a SCIM filter (RFC 7644 section 3.4.2.2). */
    static String quote(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    static String encodeQuery(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    static String encodePath(String segment) {
        return URLEncoder.encode(segment, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
