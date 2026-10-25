/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 Shebang Labs
 */
package io.shebanglabs.scimoutbound.http;

import java.time.Duration;
import java.util.Optional;

/**
 * A SCIM operation could not be completed. {@link #isRetryable()} tells the worker whether to
 * back off and try again or to mark the row FAILED. Messages are safe to store: they never
 * contain credentials and server error details are sanitised and truncated.
 */
public class ScimException extends Exception {

    private final boolean retryable;
    private final transient ScimResponse response;

    public ScimException(String message, ScimResponse response) {
        super(message);
        this.response = response;
        Outcome outcome = response == null ? Outcome.RETRY : response.outcome();
        this.retryable = outcome == Outcome.RETRY || outcome == Outcome.PRECONDITION_FAILED;
    }

    public ScimException(String message, Throwable cause, boolean retryable) {
        super(message, cause);
        this.retryable = retryable;
        this.response = null;
    }

    public boolean isRetryable() {
        return retryable;
    }

    public Optional<ScimResponse> response() {
        return Optional.ofNullable(response);
    }

    /** Server-requested delay (429/503 {@code Retry-After}), if any. */
    public Optional<Duration> retryAfter() {
        return response == null ? Optional.empty() : response.retryAfter();
    }
}
