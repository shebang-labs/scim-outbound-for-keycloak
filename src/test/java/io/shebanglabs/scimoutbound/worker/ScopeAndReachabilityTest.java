/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 Shebang Labs
 */
package io.shebanglabs.scimoutbound.worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.shebanglabs.scimoutbound.http.ScimException;
import io.shebanglabs.scimoutbound.http.ScimResponse;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.keycloak.models.GroupModel;

class ScopeAndReachabilityTest {

    @Test
    void anyMissingScopeGroupMakesTheScopeBroken() {
        // a renamed /sales would otherwise put every sales member out of scope and deprovision them
        GroupModel engineering = mock(GroupModel.class);
        when(engineering.getId()).thenReturn("g-eng");

        ScopeEvaluator partial = new ScopeEvaluator(true, List.of(engineering), List.of("/sales"));
        ScopeEvaluator complete = new ScopeEvaluator(true, List.of(engineering), List.of());
        ScopeEvaluator unrestricted = new ScopeEvaluator(false, List.of(), List.of());

        assertThat(partial.isMisconfigured()).isTrue();
        assertThat(partial.problem()).contains("/sales");
        assertThat(complete.isMisconfigured()).isFalse();
        assertThat(complete.problem()).isNull();
        assertThat(unrestricted.isMisconfigured()).isFalse();
    }

    @Test
    void onlyNetworkErrorsAndRateLimitsCountAsTargetNotAnswering() {
        ScimException timeout = new ScimException("read timed out", null, true);
        ScimException rateLimited = new ScimException("slow down", new ScimResponse(429, "", Optional.empty()));
        ScimException serverError = new ScimException("oops", new ScimResponse(503, "", Optional.empty()));
        ScimException badRequest = new ScimException("bad", new ScimResponse(400, "", Optional.empty()));

        assertThat(OutboxWorker.targetAnswered(timeout)).isFalse();
        assertThat(OutboxWorker.targetAnswered(rateLimited)).isFalse();
        assertThat(OutboxWorker.targetAnswered(serverError)).isTrue();
        assertThat(OutboxWorker.targetAnswered(badRequest)).isTrue();
    }
}
