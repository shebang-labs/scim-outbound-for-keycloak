/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 Shebang Labs
 */
package io.shebanglabs.scimoutbound.worker;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.deleteRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.patch;
import static com.github.tomakehurst.wiremock.client.WireMock.patchRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.put;
import static com.github.tomakehurst.wiremock.client.WireMock.putRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import io.shebanglabs.scimoutbound.auth.BearerAuthenticator;
import io.shebanglabs.scimoutbound.config.DeprovisionMode;
import io.shebanglabs.scimoutbound.config.TargetConfig;
import io.shebanglabs.scimoutbound.config.UserUpdateMode;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * {@code userUpdateMode} against a mock that behaves like the 1Password SCIM Bridge 2.9: it
 * advertises PATCH, answers a path-form user PATCH with 400, and accepts PUT.
 */
class UserUpdateModeTest {

    @RegisterExtension
    static WireMockExtension wm = WireMockExtension.newInstance().options(wireMockConfig().dynamicPort()).build();

    /** The account that already exists on the target, with attributes this plugin does not model. */
    private static final String REMOTE_ALICE = "{\"schemas\":[\"" + ScimSchemas.USER + "\"],"
            + "\"id\":\"r-op\",\"userName\":\"alice\",\"displayName\":\"Old Name\","
            + "\"name\":{\"givenName\":\"Old\",\"familyName\":\"Name\",\"honorificPrefix\":\"Dr\"},"
            + "\"emails\":[{\"value\":\"alice@acme.test\",\"primary\":true}],\"active\":false,"
            + "\"phoneNumbers\":[{\"value\":\"+1 555 0100\",\"type\":\"work\"}],"
            + "\"groups\":[{\"value\":\"g-1\"}],\"meta\":{\"resourceType\":\"User\"}}";

    private static final ScimUser ALICE = new ScimUser(List.of(ScimSchemas.USER), null, "kc-1", "alice",
            ScimName.of("Alice", "Ng"), "Alice Ng", null, null, null, List.of(ScimEmail.work("alice@acme.test")),
            true, null);

    private final CloseableHttpClient httpClient = HttpClients.createDefault();

    @AfterEach
    void close() throws Exception {
        httpClient.close();
    }

    private SyncExecutor executor(UserUpdateMode mode, DeprovisionMode deprovision, boolean patchSupported) {
        TargetConfig config = new TargetConfig("onepassword", true, "https://x", null, deprovision, true, null,
                null, null, null, true, mode);
        ScimHttp http = new ScimHttp(httpClient, Duration.ofSeconds(2), Duration.ofSeconds(2), 1 << 20,
                Clock.systemUTC());
        return new SyncExecutor(new ScimClient(http, URI.create(wm.baseUrl()), new BearerAuthenticator("t")),
                config, patchSupported);
    }

    private static UserState alice(String remoteId, boolean inScope) {
        return new UserState("kc-1", ALICE, inScope, remoteId, List.of());
    }

    /** The bridge: lookup by userName finds Alice, path-form PATCH is a 400, GET and PUT work. */
    private static void onePasswordBridge() {
        wm.stubFor(get(urlPathEqualTo("/Users")).willReturn(aResponse()
                .withBody("{\"totalResults\":1,\"Resources\":[" + REMOTE_ALICE + "]}")));
        wm.stubFor(get(urlEqualTo("/Users/r-op")).willReturn(aResponse().withBody(REMOTE_ALICE)));
        wm.stubFor(patch(urlPathMatching("/Users/.*"))
                .withRequestBody(matchingJsonPath("$.Operations[0].path"))
                .willReturn(aResponse().withStatus(400).withBody("{\"schemas\":[\"urn:ietf:params:scim:api:"
                        + "messages:2.0:Error\"],\"status\":\"400\",\"detail\":\"operation did not fit expected"
                        + " field operation structure\"}")));
        wm.stubFor(put(urlEqualTo("/Users/r-op")).willReturn(aResponse().withStatus(200).withBody(REMOTE_ALICE)));
    }

    /** What Alice looks like after the merge: our attributes, the bridge's other ones kept. */
    private static final String MERGED_ALICE = "{\"schemas\":[\"" + ScimSchemas.USER + "\"],"
            + "\"id\":\"r-op\",\"externalId\":\"kc-1\",\"userName\":\"alice\",\"displayName\":\"Alice Ng\","
            + "\"name\":{\"givenName\":\"Alice\",\"familyName\":\"Ng\",\"honorificPrefix\":\"Dr\"},"
            + "\"emails\":[{\"value\":\"alice@acme.test\",\"type\":\"work\",\"primary\":true}],\"active\":true,"
            + "\"phoneNumbers\":[{\"value\":\"+1 555 0100\",\"type\":\"work\"}]}";

    // ---- PUT mode ------------------------------------------------------------------------------

    @Test
    void putModeLinksExistingAccountAndUpdatesItWithMergedPut() throws Exception {
        onePasswordBridge();
        SyncExecutor ex = executor(UserUpdateMode.PUT, null, true);

        ex.upsertUser(alice(null, true));

        assertThat(ex.changes()).containsExactly(MappingChange.link(ResourceType.USER, "kc-1", "r-op"));
        wm.verify(0, patchRequestedFor(anyUrl()));
        // strict: meta and groups (read-only) are gone, nothing else is added
        wm.verify(putRequestedFor(urlEqualTo("/Users/r-op")).withRequestBody(equalToJson(MERGED_ALICE)));
    }

    @Test
    void putModeUpdatesMappedUser() throws Exception {
        onePasswordBridge();
        SyncExecutor ex = executor(UserUpdateMode.PUT, null, true);

        ex.upsertUser(alice("r-op", true));

        assertThat(ex.changes()).isEmpty();
        wm.verify(0, patchRequestedFor(anyUrl()));
        wm.verify(0, getRequestedFor(urlPathEqualTo("/Users"))); // no lookup by userName
        wm.verify(putRequestedFor(urlEqualTo("/Users/r-op")).withRequestBody(equalToJson(MERGED_ALICE)));
    }

    @Test
    void putModeIsUsedEvenWhenServerDoesNotAdvertisePatch() throws Exception {
        onePasswordBridge();

        executor(UserUpdateMode.PUT, null, false).upsertUser(alice("r-op", true));

        // a merged PUT (read first), not the blind PUT of AUTO mode on PUT-only servers
        wm.verify(getRequestedFor(urlEqualTo("/Users/r-op")));
        wm.verify(putRequestedFor(urlEqualTo("/Users/r-op")).withRequestBody(equalToJson(MERGED_ALICE)));
    }

    @Test
    void putModeRecreatesUserThatIsGoneOnTheTarget() throws Exception {
        wm.stubFor(get(urlEqualTo("/Users/r-gone")).willReturn(aResponse().withStatus(404)));
        wm.stubFor(get(urlPathEqualTo("/Users")).willReturn(aResponse()
                .withBody("{\"totalResults\":0,\"Resources\":[]}")));
        wm.stubFor(post("/Users").willReturn(aResponse().withStatus(201).withBody("{\"id\":\"r-new\"}")));
        SyncExecutor ex = executor(UserUpdateMode.PUT, null, true);

        ex.upsertUser(alice("r-gone", true));

        assertThat(ex.changes()).containsExactly(
                MappingChange.unlink(ResourceType.USER, "kc-1"),
                MappingChange.link(ResourceType.USER, "kc-1", "r-new"));
    }

    // ---- DEACTIVATE ----------------------------------------------------------------------------

    @Test
    void putModeDeactivatesDeletedUserWithPutAndNeverDeletesIt() throws Exception {
        onePasswordBridge();
        SyncExecutor ex = executor(UserUpdateMode.PUT, DeprovisionMode.DEACTIVATE, true);

        ex.deleteUser("kc-1", "r-op");

        wm.verify(0, patchRequestedFor(anyUrl()));
        wm.verify(0, deleteRequestedFor(anyUrl()));
        wm.verify(putRequestedFor(urlEqualTo("/Users/r-op")).withRequestBody(equalToJson(
                "{\"schemas\":[\"" + ScimSchemas.USER + "\"],\"id\":\"r-op\",\"userName\":\"alice\","
                        + "\"displayName\":\"Old Name\",\"name\":{\"givenName\":\"Old\",\"familyName\":\"Name\","
                        + "\"honorificPrefix\":\"Dr\"},\"emails\":[{\"value\":\"alice@acme.test\",\"primary\":true}],"
                        + "\"active\":false,\"phoneNumbers\":[{\"value\":\"+1 555 0100\",\"type\":\"work\"}]}")));
        assertThat(ex.changes()).containsExactly(MappingChange.unlink(ResourceType.USER, "kc-1"));
    }

    @Test
    void putModeDeactivatesUserLeavingScopeAndKeepsTheLink() throws Exception {
        onePasswordBridge();
        SyncExecutor ex = executor(UserUpdateMode.PUT, DeprovisionMode.DEACTIVATE, true);

        ex.upsertUser(alice("r-op", false));

        wm.verify(0, patchRequestedFor(anyUrl()));
        wm.verify(0, deleteRequestedFor(anyUrl()));
        wm.verify(putRequestedFor(urlEqualTo("/Users/r-op"))
                .withRequestBody(matchingJsonPath("$[?(@.active == false)]")));
        assertThat(ex.changes()).isEmpty(); // kept, so re-entering the scope reactivates the account
    }

    @Test
    void autoModeFallsBackToPutForDeactivation() throws Exception {
        onePasswordBridge();
        SyncExecutor ex = executor(null, DeprovisionMode.DEACTIVATE, true);

        ex.deleteUser("kc-1", "r-op");

        wm.verify(1, patchRequestedFor(urlEqualTo("/Users/r-op")));
        wm.verify(putRequestedFor(urlEqualTo("/Users/r-op"))
                .withRequestBody(matchingJsonPath("$[?(@.active == false)]")));
        wm.verify(0, deleteRequestedFor(anyUrl()));
        assertThat(ex.changes()).containsExactly(MappingChange.unlink(ResourceType.USER, "kc-1"));
    }

    // ---- AUTO mode -----------------------------------------------------------------------------

    @Test
    void autoModeRetriesRejectedPatchOnceAsPutAndSavesTheLink() throws Exception {
        onePasswordBridge();
        SyncExecutor ex = executor(UserUpdateMode.AUTO, null, true);

        ex.upsertUser(alice(null, true));

        assertThat(ex.changes()).containsExactly(MappingChange.link(ResourceType.USER, "kc-1", "r-op"));
        wm.verify(1, patchRequestedFor(urlEqualTo("/Users/r-op")));
        wm.verify(1, putRequestedFor(urlEqualTo("/Users/r-op")).withRequestBody(equalToJson(MERGED_ALICE)));
    }

    @Test
    void autoModeStillPatchesServersThatAcceptPatch() throws Exception {
        wm.stubFor(get(urlPathEqualTo("/Users")).willReturn(aResponse()
                .withBody("{\"totalResults\":1,\"Resources\":[" + REMOTE_ALICE + "]}")));
        wm.stubFor(patch(urlEqualTo("/Users/r-op")).willReturn(aResponse().withStatus(200)));
        SyncExecutor ex = executor(null, null, true); // userUpdateMode not set at all

        ex.upsertUser(alice(null, true));

        assertThat(ex.changes()).containsExactly(MappingChange.link(ResourceType.USER, "kc-1", "r-op"));
        wm.verify(patchRequestedFor(urlEqualTo("/Users/r-op"))
                .withRequestBody(matchingJsonPath("$.Operations[?(@.path == 'active')]")));
        wm.verify(0, getRequestedFor(urlEqualTo("/Users/r-op")));
        wm.verify(0, putRequestedFor(anyUrl()));
    }

    @Test
    void autoModeStillPatchesDeactivationOnServersThatAcceptPatch() throws Exception {
        wm.stubFor(patch(urlEqualTo("/Users/r-1")).willReturn(aResponse().withStatus(204)));

        executor(null, DeprovisionMode.DEACTIVATE, true).deleteUser("kc-1", "r-1");

        wm.verify(patchRequestedFor(urlEqualTo("/Users/r-1")).withRequestBody(equalToJson(
                "{\"schemas\":[\"" + ScimSchemas.PATCH_OP + "\"],"
                        + "\"Operations\":[{\"op\":\"replace\",\"path\":\"active\",\"value\":false}]}")));
        wm.verify(0, putRequestedFor(anyUrl()));
    }

    @ParameterizedTest
    @ValueSource(ints = {401, 403, 409, 422})
    void autoModeDoesNotFallBackOnOtherErrors(int status) {
        wm.stubFor(patch(urlEqualTo("/Users/r-op")).willReturn(aResponse().withStatus(status)));
        SyncExecutor ex = executor(UserUpdateMode.AUTO, null, true);

        assertThatThrownBy(() -> ex.upsertUser(alice("r-op", true))).isInstanceOf(ScimException.class);
        wm.verify(0, getRequestedFor(urlEqualTo("/Users/r-op")));
        wm.verify(0, putRequestedFor(anyUrl()));
    }

    @Test
    void autoModeTreats404AsGoneNotAsFallback() throws Exception {
        wm.stubFor(patch(urlEqualTo("/Users/r-old")).willReturn(aResponse().withStatus(404)));
        wm.stubFor(get(urlPathEqualTo("/Users")).willReturn(aResponse()
                .withBody("{\"totalResults\":0,\"Resources\":[]}")));
        wm.stubFor(post("/Users").willReturn(aResponse().withStatus(201).withBody("{\"id\":\"r-new\"}")));
        SyncExecutor ex = executor(UserUpdateMode.AUTO, null, true);

        ex.upsertUser(alice("r-old", true));

        wm.verify(0, putRequestedFor(anyUrl()));
        wm.verify(postRequestedFor(urlEqualTo("/Users")));
    }

    @Test
    void patchModeNeverFallsBack() {
        onePasswordBridge();
        SyncExecutor ex = executor(UserUpdateMode.PATCH, null, true);

        assertThatThrownBy(() -> ex.upsertUser(alice(null, true)))
                .isInstanceOf(ScimException.class)
                .hasMessageContaining("user update after link failed: HTTP 400");
        wm.verify(0, putRequestedFor(anyUrl()));
        assertThat(ex.changes()).isEmpty();
    }

    // ---- failed links are not saved ----------------------------------------------------------

    @Test
    void failedUpdateAfterLookupLeavesNoLink() {
        onePasswordBridge();
        wm.stubFor(put(urlEqualTo("/Users/r-op")).willReturn(aResponse().withStatus(400)
                .withBody("{\"status\":\"400\",\"detail\":\"invalid user\"}")));
        SyncExecutor ex = executor(UserUpdateMode.PUT, null, true);

        assertThatThrownBy(() -> ex.upsertUser(alice(null, true)))
                .isInstanceOf(ScimException.class)
                .hasMessageContaining("user update after link failed: HTTP 400")
                .satisfies(e -> assertThat(((ScimException) e).isRetryable()).isFalse());
        assertThat(ex.changes()).isEmpty();
    }

    @Test
    void failedFallbackNamesBothAnswersAndLeavesNoLink() {
        onePasswordBridge();
        wm.stubFor(put(urlEqualTo("/Users/r-op")).willReturn(aResponse().withStatus(400)
                .withBody("{\"status\":\"400\",\"detail\":\"invalid user\"}")));
        SyncExecutor ex = executor(UserUpdateMode.AUTO, null, true);

        assertThatThrownBy(() -> ex.upsertUser(alice(null, true)))
                .isInstanceOf(ScimException.class)
                .hasMessageContaining("PATCH answered HTTP 400")
                .hasMessageContaining("then the PUT fallback got HTTP 400: invalid user")
                .satisfies(e -> assertThat(((ScimException) e).isRetryable()).isFalse());
        assertThat(ex.changes()).isEmpty();
    }

    @Test
    void fallbackNeverTurnsIntoRecreate() {
        // the PATCH found the user, so a 404 from the read before the PUT must not re-create it
        wm.stubFor(patch(urlEqualTo("/Users/r-op")).willReturn(aResponse().withStatus(400)));
        wm.stubFor(get(urlEqualTo("/Users/r-op")).willReturn(aResponse().withStatus(404)));
        SyncExecutor ex = executor(UserUpdateMode.AUTO, null, true);

        assertThatThrownBy(() -> ex.upsertUser(alice("r-op", true)))
                .isInstanceOf(ScimException.class)
                .hasMessageContaining("then the PUT fallback got HTTP 404")
                .satisfies(e -> assertThat(((ScimException) e).isRetryable()).isFalse());
        wm.verify(0, postRequestedFor(anyUrl()));
        assertThat(ex.changes()).isEmpty();
    }

    @Test
    void retryableFailureAfterLookupLeavesNoLinkEither() {
        onePasswordBridge();
        wm.stubFor(put(urlEqualTo("/Users/r-op")).willReturn(aResponse().withStatus(503)));
        SyncExecutor ex = executor(UserUpdateMode.PUT, null, true);

        assertThatThrownBy(() -> ex.upsertUser(alice(null, true)))
                .isInstanceOf(ScimException.class)
                .satisfies(e -> assertThat(((ScimException) e).isRetryable()).isTrue());
        assertThat(ex.changes()).isEmpty(); // the retry looks the account up again
    }

    @Test
    void staleLinkIsRemovedWhenTheRelinkFails() {
        // mapped to an id the target no longer knows; the account found by userName rejects the update
        onePasswordBridge();
        wm.stubFor(patch(urlEqualTo("/Users/r-stale")).willReturn(aResponse().withStatus(404)));
        SyncExecutor ex = executor(UserUpdateMode.PATCH, null, true);

        assertThatThrownBy(() -> ex.upsertUser(alice("r-stale", true))).isInstanceOf(ScimException.class);
        assertThat(ex.changes()).containsExactly(MappingChange.unlink(ResourceType.USER, "kc-1"));
    }
}
