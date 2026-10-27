/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 Shebang Labs
 */
package io.shebanglabs.scimoutbound.worker;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.delete;
import static com.github.tomakehurst.wiremock.client.WireMock.deleteRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.patch;
import static com.github.tomakehurst.wiremock.client.WireMock.patchRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.put;
import static com.github.tomakehurst.wiremock.client.WireMock.putRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import io.shebanglabs.scimoutbound.auth.BearerAuthenticator;
import io.shebanglabs.scimoutbound.config.DeprovisionMode;
import io.shebanglabs.scimoutbound.config.TargetConfig;
import io.shebanglabs.scimoutbound.http.ScimClient;
import io.shebanglabs.scimoutbound.http.ScimException;
import io.shebanglabs.scimoutbound.http.ScimHttp;
import io.shebanglabs.scimoutbound.outbox.ResourceType;
import io.shebanglabs.scimoutbound.scim.ScimEmail;
import io.shebanglabs.scimoutbound.scim.ScimName;
import io.shebanglabs.scimoutbound.scim.ScimSchemas;
import io.shebanglabs.scimoutbound.scim.ScimUser;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

class SyncExecutorTest {

    @RegisterExtension
    static WireMockExtension wm = WireMockExtension.newInstance().options(wireMockConfig().dynamicPort()).build();

    private static final String EMPTY_LIST = "{\"totalResults\":0,\"Resources\":[]}";
    private static final ScimUser ALICE = new ScimUser(List.of(ScimSchemas.USER), null, "kc-1", "alice",
            ScimName.of("Alice", "Ng"), "Alice Ng", null, null, null, List.of(ScimEmail.work("alice@acme.test")),
            true, null);

    private final CloseableHttpClient httpClient = HttpClients.createDefault();

    @AfterEach
    void close() throws Exception {
        httpClient.close();
    }

    private SyncExecutor executor(TargetConfig config, boolean patch) {
        ScimHttp http = new ScimHttp(httpClient, Duration.ofSeconds(2), Duration.ofSeconds(2), 1 << 20,
                Clock.systemUTC());
        return new SyncExecutor(new ScimClient(http, URI.create(wm.baseUrl()), new BearerAuthenticator("t")),
                config, patch);
    }

    private static TargetConfig config(DeprovisionMode mode, boolean groups) {
        return new TargetConfig("t", true, "https://x", null, mode, groups, null, null, null, null, null);
    }

    private static TargetConfig linkingExisting(boolean groups) {
        return new TargetConfig("t", true, "https://x", null, null, groups, null, null, null, null, true);
    }

    private static UserState alice(String remoteId, boolean inScope, List<String> groups) {
        return new UserState("kc-1", ALICE, inScope, remoteId, groups);
    }

    // ---- users -------------------------------------------------------------------------------

    @Test
    void createsUnknownUserAfterLookup() throws Exception {
        wm.stubFor(get(urlPathEqualTo("/Users")).willReturn(aResponse().withBody(EMPTY_LIST)));
        wm.stubFor(post("/Users").willReturn(aResponse().withStatus(201).withBody("{\"id\":\"r-1\"}")));
        SyncExecutor ex = executor(config(null, false), true);

        ex.upsertUser(alice(null, true, List.of()));

        assertThat(ex.changes()).containsExactly(MappingChange.link(ResourceType.USER, "kc-1", "r-1"));
        wm.verify(postRequestedFor(urlEqualTo("/Users")).withRequestBody(equalToJson(
                "{\"schemas\":[\"" + ScimSchemas.USER + "\"],\"externalId\":\"kc-1\",\"userName\":\"alice\","
                        + "\"name\":{\"givenName\":\"Alice\",\"familyName\":\"Ng\"},\"displayName\":\"Alice Ng\","
                        + "\"emails\":[{\"value\":\"alice@acme.test\",\"type\":\"work\",\"primary\":true}],"
                        + "\"active\":true}")));
    }

    @Test
    void relinksOwnRemoteUserInsteadOfCreatingDuplicate() throws Exception {
        // e.g. the user was created but the node crashed before the link was saved
        wm.stubFor(get(urlPathEqualTo("/Users")).willReturn(aResponse().withBody(
                "{\"totalResults\":1,\"Resources\":[{\"id\":\"r-9\",\"externalId\":\"kc-1\",\"userName\":\"alice\"}]}")));
        wm.stubFor(patch(urlEqualTo("/Users/r-9")).willReturn(aResponse().withStatus(200)));
        SyncExecutor ex = executor(config(null, false), true);

        ex.upsertUser(alice(null, true, List.of()));

        assertThat(ex.changes()).containsExactly(MappingChange.link(ResourceType.USER, "kc-1", "r-9"));
        wm.verify(0, postRequestedFor(urlEqualTo("/Users")));
        wm.verify(patchRequestedFor(urlEqualTo("/Users/r-9")));
    }

    @Test
    void leavesForeignRemoteUserWithSameUserNameUntouched() {
        wm.stubFor(get(urlPathEqualTo("/Users")).willReturn(aResponse().withBody(
                "{\"totalResults\":1,\"Resources\":[{\"id\":\"r-real\",\"externalId\":\"someone-else\","
                        + "\"userName\":\"alice\"}]}")));
        SyncExecutor ex = executor(config(null, false), true);

        assertThatThrownBy(() -> ex.upsertUser(alice(null, true, List.of())))
                .isInstanceOf(ScimException.class)
                .hasMessageContaining("left untouched")
                .satisfies(e -> assertThat(((ScimException) e).isRetryable()).isFalse());
        assertThat(ex.changes()).isEmpty();
        wm.verify(1, anyRequestedFor(anyUrl())); // the lookup only: no create, no update
    }

    @Test
    void takesOverForeignRemoteUserWhenAllowed() throws Exception {
        wm.stubFor(get(urlPathEqualTo("/Users")).willReturn(aResponse().withBody(
                "{\"totalResults\":1,\"Resources\":[{\"id\":\"r-9\",\"userName\":\"alice\"}]}")));
        wm.stubFor(patch(urlEqualTo("/Users/r-9")).willReturn(aResponse().withStatus(200)));
        SyncExecutor ex = executor(linkingExisting(false), true);

        ex.upsertUser(alice(null, true, List.of()));

        assertThat(ex.changes()).containsExactly(MappingChange.link(ResourceType.USER, "kc-1", "r-9"));
        wm.verify(patchRequestedFor(urlEqualTo("/Users/r-9")));
    }

    @Test
    void conflictOnCreateBecomesLink() throws Exception {
        wm.stubFor(get(urlPathEqualTo("/Users")).inScenario("c").whenScenarioStateIs("Started")
                .willReturn(aResponse().withBody(EMPTY_LIST)).willSetStateTo("created"));
        wm.stubFor(post("/Users").willReturn(aResponse().withStatus(409)
                .withBody("{\"status\":\"409\",\"scimType\":\"uniqueness\"}")));
        wm.stubFor(get(urlPathEqualTo("/Users")).inScenario("c").whenScenarioStateIs("created")
                .willReturn(aResponse().withBody(
                        "{\"totalResults\":1,\"Resources\":[{\"id\":\"r-2\",\"externalId\":\"kc-1\"}]}")));
        wm.stubFor(patch(urlEqualTo("/Users/r-2")).willReturn(aResponse().withStatus(204)));
        SyncExecutor ex = executor(config(null, false), true);

        ex.upsertUser(alice(null, true, List.of()));

        assertThat(ex.changes()).containsExactly(MappingChange.link(ResourceType.USER, "kc-1", "r-2"));
    }

    @Test
    void conflictWithoutSameUserNamePointsAtAnotherUniqueValue() {
        // AWS answers 409 when the email address belongs to another account
        wm.stubFor(get(urlPathEqualTo("/Users")).willReturn(aResponse().withBody(EMPTY_LIST)));
        wm.stubFor(post("/Users").willReturn(aResponse().withStatus(409)
                .withBody("{\"status\":\"409\",\"detail\":\"Duplicate email\"}")));
        SyncExecutor ex = executor(config(null, false), true);

        assertThatThrownBy(() -> ex.upsertUser(alice(null, true, List.of())))
                .isInstanceOf(ScimException.class)
                .hasMessageContaining("HTTP 409: Duplicate email")
                .hasMessageContaining("usually the email address");
    }

    @Test
    void createWithoutIdInBodyFallsBackToLookup() throws Exception {
        wm.stubFor(get(urlPathEqualTo("/Users")).inScenario("e").whenScenarioStateIs("Started")
                .willReturn(aResponse().withBody(EMPTY_LIST)).willSetStateTo("after"));
        wm.stubFor(post("/Users").willReturn(aResponse().withStatus(201)));
        wm.stubFor(get(urlPathEqualTo("/Users")).inScenario("e").whenScenarioStateIs("after")
                .willReturn(aResponse().withBody("{\"totalResults\":1,\"Resources\":[{\"id\":\"r-3\"}]}")));
        SyncExecutor ex = executor(config(null, false), true);

        ex.upsertUser(alice(null, true, List.of()));

        assertThat(ex.changes()).containsExactly(MappingChange.link(ResourceType.USER, "kc-1", "r-3"));
    }

    @Test
    void mappedUserIsPatchedWithoutLookup() throws Exception {
        wm.stubFor(patch(urlEqualTo("/Users/r-1")).willReturn(aResponse().withStatus(200)));
        SyncExecutor ex = executor(config(null, false), true);

        ex.upsertUser(alice("r-1", true, List.of()));

        assertThat(ex.changes()).isEmpty();
        wm.verify(1, anyRequestedFor(anyUrl()));
    }

    @Test
    void mappedUserUsesPutWhenPatchUnsupported() throws Exception {
        wm.stubFor(put(urlEqualTo("/Users/r-1")).willReturn(aResponse().withStatus(200)));

        executor(config(null, false), false).upsertUser(alice("r-1", true, List.of()));

        wm.verify(putRequestedFor(urlEqualTo("/Users/r-1")));
    }

    @Test
    void remotelyDeletedUserIsRecreated() throws Exception {
        wm.stubFor(patch(urlEqualTo("/Users/r-old")).willReturn(aResponse().withStatus(404)));
        wm.stubFor(get(urlPathEqualTo("/Users")).willReturn(aResponse().withBody(EMPTY_LIST)));
        wm.stubFor(post("/Users").willReturn(aResponse().withStatus(201).withBody("{\"id\":\"r-new\"}")));
        SyncExecutor ex = executor(config(null, false), true);

        ex.upsertUser(alice("r-old", true, List.of()));

        assertThat(ex.changes()).containsExactly(
                MappingChange.unlink(ResourceType.USER, "kc-1"),
                MappingChange.link(ResourceType.USER, "kc-1", "r-new"));
    }

    @Test
    void newlyLinkedUserIsAddedToItsSyncedGroups() throws Exception {
        wm.stubFor(get(urlPathEqualTo("/Users")).willReturn(aResponse().withBody(EMPTY_LIST)));
        wm.stubFor(post("/Users").willReturn(aResponse().withStatus(201).withBody("{\"id\":\"r-1\"}")));
        wm.stubFor(patch(urlEqualTo("/Groups/g-r")).willReturn(aResponse().withStatus(204)));

        executor(config(null, true), true).upsertUser(alice(null, true, List.of("g-r")));

        wm.verify(patchRequestedFor(urlEqualTo("/Groups/g-r")).withRequestBody(equalToJson(
                "{\"schemas\":[\"" + ScimSchemas.PATCH_OP + "\"],\"Operations\":[{\"op\":\"add\","
                        + "\"path\":\"members\",\"value\":[{\"value\":\"r-1\"}]}]}")));
    }

    @Test
    void deletedUserIsDeactivatedByDefaultAndUnlinked() throws Exception {
        wm.stubFor(patch(urlEqualTo("/Users/r-1")).willReturn(aResponse().withStatus(200)));
        SyncExecutor ex = executor(config(null, false), true);

        ex.deleteUser("kc-1", "r-1");

        wm.verify(patchRequestedFor(urlEqualTo("/Users/r-1")).withRequestBody(equalToJson(
                "{\"schemas\":[\"" + ScimSchemas.PATCH_OP + "\"],"
                        + "\"Operations\":[{\"op\":\"replace\",\"path\":\"active\",\"value\":false}]}")));
        assertThat(ex.changes()).containsExactly(MappingChange.unlink(ResourceType.USER, "kc-1"));
    }

    @Test
    void deactivationWithoutPatchReadsThenPuts() throws Exception {
        wm.stubFor(get(urlEqualTo("/Users/r-1")).willReturn(aResponse()
                .withBody("{\"id\":\"r-1\",\"userName\":\"alice\",\"active\":true}")));
        wm.stubFor(put(urlEqualTo("/Users/r-1")).willReturn(aResponse().withStatus(200)));

        executor(config(null, false), false).deleteUser("kc-1", "r-1");

        wm.verify(putRequestedFor(urlEqualTo("/Users/r-1"))
                .withRequestBody(equalToJson("{\"id\":\"r-1\",\"userName\":\"alice\",\"active\":false}", true, true)));
    }

    @Test
    void deleteModeSendsDeleteAndTolerates404() throws Exception {
        wm.stubFor(delete(urlEqualTo("/Users/r-1")).willReturn(aResponse().withStatus(404)));
        SyncExecutor ex = executor(config(DeprovisionMode.DELETE, false), true);

        ex.deleteUser("kc-1", "r-1");

        wm.verify(deleteRequestedFor(urlEqualTo("/Users/r-1")));
        assertThat(ex.changes()).containsExactly(MappingChange.unlink(ResourceType.USER, "kc-1"));
    }

    @Test
    void unmappedDeletedUserNeedsNoRequest() throws Exception {
        executor(config(null, false), true).deleteUser("kc-1", null);

        wm.verify(0, anyRequestedFor(anyUrl()));
    }

    @Test
    void userLeavingScopeIsDeactivatedButStaysLinked() throws Exception {
        wm.stubFor(patch(urlEqualTo("/Users/r-1")).willReturn(aResponse().withStatus(200)));
        SyncExecutor ex = executor(config(null, false), true);

        ex.upsertUser(alice("r-1", false, List.of()));

        wm.verify(patchRequestedFor(urlEqualTo("/Users/r-1")));
        assertThat(ex.changes()).isEmpty();
    }

    @Test
    void outOfScopeUnmappedUserIsIgnored() throws Exception {
        executor(config(null, false), true).upsertUser(alice(null, false, List.of()));

        wm.verify(0, anyRequestedFor(anyUrl()));
    }

    @Test
    void badRequestIsPermanentServerErrorIsRetryable() {
        wm.stubFor(patch(urlEqualTo("/Users/bad")).willReturn(aResponse().withStatus(400)
                .withBody("{\"detail\":\"invalid email\"}")));
        wm.stubFor(patch(urlEqualTo("/Users/down")).willReturn(aResponse().withStatus(503)));
        wm.stubFor(patch(urlEqualTo("/Users/slow")).willReturn(aResponse().withStatus(429)
                .withHeader("Retry-After", "42")));
        SyncExecutor ex = executor(config(null, false), true);

        assertThatThrownBy(() -> ex.upsertUser(alice("bad", true, List.of())))
                .isInstanceOf(ScimException.class)
                .hasMessageContaining("invalid email")
                .satisfies(e -> assertThat(((ScimException) e).isRetryable()).isFalse());
        assertThatThrownBy(() -> ex.upsertUser(alice("down", true, List.of())))
                .satisfies(e -> assertThat(((ScimException) e).isRetryable()).isTrue());
        assertThatThrownBy(() -> ex.upsertUser(alice("slow", true, List.of())))
                .satisfies(e -> {
                    ScimException se = (ScimException) e;
                    assertThat(se.isRetryable()).isTrue();
                    assertThat(se.retryAfter()).contains(Duration.ofSeconds(42));
                });
    }

    @Test
    void mappingChangesSurviveALaterFailure() {
        wm.stubFor(get(urlPathEqualTo("/Users")).willReturn(aResponse().withBody(EMPTY_LIST)));
        wm.stubFor(post("/Users").willReturn(aResponse().withStatus(201).withBody("{\"id\":\"r-1\"}")));
        wm.stubFor(patch(urlEqualTo("/Groups/g-r")).willReturn(aResponse().withStatus(500)));
        SyncExecutor ex = executor(config(null, true), true);

        assertThatThrownBy(() -> ex.upsertUser(alice(null, true, List.of("g-r")))).isInstanceOf(ScimException.class);
        assertThat(ex.changes()).containsExactly(MappingChange.link(ResourceType.USER, "kc-1", "r-1"));
    }

    // ---- groups ------------------------------------------------------------------------------

    @Test
    void groupsAreIgnoredWhenGroupSyncIsOff() throws Exception {
        executor(config(null, false), true).upsertGroup(new GroupState("g1", "eng", true, null, List.of()));

        wm.verify(0, anyRequestedFor(anyUrl()));
    }

    @Test
    void createsGroupWithMembers() throws Exception {
        wm.stubFor(get(urlPathEqualTo("/Groups")).willReturn(aResponse().withBody(EMPTY_LIST)));
        wm.stubFor(post("/Groups").willReturn(aResponse().withStatus(201).withBody("{\"id\":\"gr-1\"}")));
        SyncExecutor ex = executor(config(null, true), true);

        ex.upsertGroup(new GroupState("g1", "eng", true, null, List.of("r-1", "r-2")));

        assertThat(ex.changes()).containsExactly(MappingChange.link(ResourceType.GROUP, "g1", "gr-1"));
        wm.verify(postRequestedFor(urlEqualTo("/Groups")).withRequestBody(equalToJson(
                "{\"schemas\":[\"" + ScimSchemas.GROUP + "\"],\"externalId\":\"g1\",\"displayName\":\"eng\","
                        + "\"members\":[{\"value\":\"r-1\"},{\"value\":\"r-2\"}]}")));
    }

    @Test
    void leavesForeignRemoteGroupWithSameNameUntouched() {
        // replacing its members would remove people that Keycloak never added
        wm.stubFor(get(urlPathEqualTo("/Groups")).willReturn(aResponse().withBody(
                "{\"totalResults\":1,\"Resources\":[{\"id\":\"gr-real\",\"displayName\":\"eng\"}]}")));
        SyncExecutor ex = executor(config(null, true), true);

        assertThatThrownBy(() -> ex.upsertGroup(new GroupState("g1", "eng", true, null, List.of("r-1"))))
                .isInstanceOf(ScimException.class)
                .hasMessageContaining("left untouched");
        assertThat(ex.changes()).isEmpty();
        wm.verify(1, anyRequestedFor(anyUrl()));
    }

    @Test
    void relinksOwnRemoteGroup() throws Exception {
        wm.stubFor(get(urlPathEqualTo("/Groups")).willReturn(aResponse().withBody(
                "{\"totalResults\":1,\"Resources\":[{\"id\":\"gr-1\",\"externalId\":\"g1\",\"displayName\":\"eng\"}]}")));
        wm.stubFor(get(urlEqualTo("/Groups/gr-1")).willReturn(aResponse().withBody("{\"id\":\"gr-1\"}")));
        wm.stubFor(patch(urlEqualTo("/Groups/gr-1")).willReturn(aResponse().withStatus(204)));
        SyncExecutor ex = executor(config(null, true), true);

        ex.upsertGroup(new GroupState("g1", "eng", true, null, List.of("r-1")));

        assertThat(ex.changes()).containsExactly(MappingChange.link(ResourceType.GROUP, "g1", "gr-1"));
    }

    @Test
    void renamesMappedGroupWithPatch() throws Exception {
        wm.stubFor(patch(urlEqualTo("/Groups/gr-1")).willReturn(aResponse().withStatus(204)));

        executor(config(null, true), true).upsertGroup(new GroupState("g1", "engineering", true, "gr-1", List.of()));

        wm.verify(patchRequestedFor(urlEqualTo("/Groups/gr-1")).withRequestBody(equalToJson(
                "{\"schemas\":[\"" + ScimSchemas.PATCH_OP + "\"],\"Operations\":[{\"op\":\"replace\","
                        + "\"path\":\"displayName\",\"value\":\"engineering\"}]}")));
    }

    @Test
    void addMemberProvisionsUserFirstWhenNeeded() throws Exception {
        wm.stubFor(get(urlPathEqualTo("/Users")).willReturn(aResponse().withBody(EMPTY_LIST)));
        wm.stubFor(post("/Users").willReturn(aResponse().withStatus(201).withBody("{\"id\":\"r-1\"}")));
        wm.stubFor(patch(urlEqualTo("/Groups/gr-1")).willReturn(aResponse().withStatus(204)));
        SyncExecutor ex = executor(config(null, true), true);

        ex.addMember(new GroupState("g1", "eng", true, "gr-1", List.of()), alice(null, true, List.of()));

        assertThat(ex.changes()).containsExactly(MappingChange.link(ResourceType.USER, "kc-1", "r-1"));
        wm.verify(patchRequestedFor(urlEqualTo("/Groups/gr-1")).withRequestBody(equalToJson(
                "{\"schemas\":[\"" + ScimSchemas.PATCH_OP + "\"],\"Operations\":[{\"op\":\"add\","
                        + "\"path\":\"members\",\"value\":[{\"value\":\"r-1\"}]}]}")));
    }

    @Test
    void removeMemberListsTheMemberAsValue() throws Exception {
        wm.stubFor(patch(urlEqualTo("/Groups/gr-1")).willReturn(aResponse().withStatus(204)));

        executor(config(null, true), true).removeMember(new GroupState("g1", "eng", true, "gr-1", List.of()), "r-1");

        wm.verify(1, patchRequestedFor(urlEqualTo("/Groups/gr-1")));
        wm.verify(patchRequestedFor(urlEqualTo("/Groups/gr-1")).withRequestBody(equalToJson(
                "{\"schemas\":[\"" + ScimSchemas.PATCH_OP + "\"],\"Operations\":[{\"op\":\"remove\","
                        + "\"path\":\"members\",\"value\":[{\"value\":\"r-1\"}]}]}")));
    }

    @Test
    void removeMemberFallsBackToFilterPathWhenValueFormIsRejected() throws Exception {
        wm.stubFor(patch(urlEqualTo("/Groups/gr-1")).inScenario("r").whenScenarioStateIs("Started")
                .willReturn(aResponse().withStatus(400)).willSetStateTo("filter"));
        wm.stubFor(patch(urlEqualTo("/Groups/gr-1")).inScenario("r").whenScenarioStateIs("filter")
                .willReturn(aResponse().withStatus(204)));

        executor(config(null, true), true).removeMember(new GroupState("g1", "eng", true, "gr-1", List.of()), "r-1");

        wm.verify(patchRequestedFor(urlEqualTo("/Groups/gr-1")).withRequestBody(equalToJson(
                "{\"schemas\":[\"" + ScimSchemas.PATCH_OP + "\"],\"Operations\":[{\"op\":\"remove\","
                        + "\"path\":\"members[value eq \\\"r-1\\\"]\"}]}")));
    }

    @Test
    void syncMembersSendsOnlyTheDifferences() throws Exception {
        wm.stubFor(get(urlEqualTo("/Groups/gr-1")).willReturn(aResponse().withBody(
                "{\"id\":\"gr-1\",\"members\":[{\"value\":\"r-1\"},{\"value\":\"r-old\"}]}")));
        wm.stubFor(patch(urlEqualTo("/Groups/gr-1")).willReturn(aResponse().withStatus(204)));

        executor(config(null, true), true).syncMembers(new GroupState("g1", "eng", true, "gr-1", List.of("r-1", "r-2")));

        wm.verify(2, patchRequestedFor(urlEqualTo("/Groups/gr-1")));
        wm.verify(patchRequestedFor(urlEqualTo("/Groups/gr-1")).withRequestBody(equalToJson(
                "{\"schemas\":[\"" + ScimSchemas.PATCH_OP + "\"],\"Operations\":[{\"op\":\"add\","
                        + "\"path\":\"members\",\"value\":[{\"value\":\"r-2\"}]}]}")));
        wm.verify(patchRequestedFor(urlEqualTo("/Groups/gr-1")).withRequestBody(equalToJson(
                "{\"schemas\":[\"" + ScimSchemas.PATCH_OP + "\"],\"Operations\":[{\"op\":\"remove\","
                        + "\"path\":\"members\",\"value\":[{\"value\":\"r-old\"}]}]}")));
    }

    @Test
    void syncMembersOnlyAddsWhenMembersCannotBeFound() throws Exception {
        // the group shows no members and users cannot be filtered by group
        wm.stubFor(get(urlEqualTo("/Groups/gr-1")).willReturn(aResponse().withBody("{\"id\":\"gr-1\"}")));
        wm.stubFor(get(urlPathEqualTo("/Users")).willReturn(aResponse().withStatus(400)));
        wm.stubFor(patch(urlEqualTo("/Groups/gr-1")).willReturn(aResponse().withStatus(204)));

        executor(config(null, true), true).syncMembers(new GroupState("g1", "eng", true, "gr-1", List.of("r-1")));

        wm.verify(1, patchRequestedFor(urlEqualTo("/Groups/gr-1")));
        wm.verify(patchRequestedFor(urlEqualTo("/Groups/gr-1")).withRequestBody(equalToJson(
                "{\"schemas\":[\"" + ScimSchemas.PATCH_OP + "\"],\"Operations\":[{\"op\":\"add\","
                        + "\"path\":\"members\",\"value\":[{\"value\":\"r-1\"}]}]}")));
    }

    @Test
    void syncMembersRemovesStrayMembersFoundThroughTheUserFilter() throws Exception {
        // AWS IAM Identity Center: GetGroup shows no members, ListUsers filters by groups.value
        wm.stubFor(get(urlEqualTo("/Groups/gr-1")).willReturn(aResponse().withBody("{\"id\":\"gr-1\"}")));
        wm.stubFor(get(urlPathEqualTo("/Users")).withQueryParam("count", com.github.tomakehurst.wiremock.client.WireMock.equalTo("1"))
                .willReturn(aResponse().withBody(EMPTY_LIST)));
        wm.stubFor(get(urlPathEqualTo("/Users")).withQueryParam("count", com.github.tomakehurst.wiremock.client.WireMock.equalTo("100"))
                .withQueryParam("filter", com.github.tomakehurst.wiremock.client.WireMock.equalTo("groups.value eq \"gr-1\""))
                .willReturn(aResponse().withBody("{\"Resources\":[{\"id\":\"r-1\"},{\"id\":\"r-stray\"}]}")));
        wm.stubFor(patch(urlEqualTo("/Groups/gr-1")).willReturn(aResponse().withStatus(204)));

        executor(config(null, true), true).syncMembers(new GroupState("g1", "eng", true, "gr-1", List.of("r-1")));

        wm.verify(patchRequestedFor(urlEqualTo("/Groups/gr-1")).withRequestBody(equalToJson(
                "{\"schemas\":[\"" + ScimSchemas.PATCH_OP + "\"],\"Operations\":[{\"op\":\"remove\","
                        + "\"path\":\"members\",\"value\":[{\"value\":\"r-stray\"}]}]}")));
    }

    @Test
    void userFilterIsNotTrustedWhenTheServerIgnoresIt() throws Exception {
        // a server that ignores the filter returns users even for a group that cannot exist
        wm.stubFor(get(urlEqualTo("/Groups/gr-1")).willReturn(aResponse().withBody("{\"id\":\"gr-1\"}")));
        wm.stubFor(get(urlPathEqualTo("/Users"))
                .willReturn(aResponse().withBody("{\"Resources\":[{\"id\":\"r-1\"},{\"id\":\"r-other\"}]}")));
        wm.stubFor(patch(urlEqualTo("/Groups/gr-1")).willReturn(aResponse().withStatus(204)));

        executor(config(null, true), true).syncMembers(new GroupState("g1", "eng", true, "gr-1", List.of("r-1")));

        wm.verify(1, patchRequestedFor(urlEqualTo("/Groups/gr-1"))); // the addition only
    }

    @Test
    void syncMembersNeverReplacesOrClearsTheWholeList() throws Exception {
        wm.stubFor(get(urlEqualTo("/Groups/gr-1")).willReturn(aResponse().withBody("{\"id\":\"gr-1\"}")));

        executor(config(null, true), true).syncMembers(new GroupState("g1", "eng", true, "gr-1", List.of()));

        wm.verify(0, patchRequestedFor(urlEqualTo("/Groups/gr-1")));
    }

    @Test
    void largeGroupIsCreatedWithAtMostOneChunkAndFilledInChunks() throws Exception {
        List<String> members = java.util.stream.IntStream.range(0, 250).mapToObj(i -> "r-" + i).toList();
        wm.stubFor(get(urlPathEqualTo("/Groups")).willReturn(aResponse().withBody(EMPTY_LIST)));
        wm.stubFor(post("/Groups").willReturn(aResponse().withStatus(201).withBody("{\"id\":\"gr-1\"}")));
        wm.stubFor(patch(urlEqualTo("/Groups/gr-1")).willReturn(aResponse().withStatus(204)));

        executor(config(null, true), true).upsertGroup(new GroupState("g1", "eng", true, null, members));

        wm.verify(postRequestedFor(urlEqualTo("/Groups"))
                .withRequestBody(com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath(
                        "$.members.length()", com.github.tomakehurst.wiremock.client.WireMock.equalTo("100"))));
        wm.verify(2, patchRequestedFor(urlEqualTo("/Groups/gr-1")));
    }

    @Test
    void deletedGroupIsDeletedRemotely() throws Exception {
        wm.stubFor(delete(urlEqualTo("/Groups/gr-1")).willReturn(aResponse().withStatus(204)));
        SyncExecutor ex = executor(config(null, true), true);

        ex.deleteGroup("g1", "gr-1");

        assertThat(ex.changes()).containsExactly(MappingChange.unlink(ResourceType.GROUP, "g1"));
    }
}
