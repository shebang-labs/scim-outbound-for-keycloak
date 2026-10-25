/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 Shebang Labs
 */
package io.shebanglabs.scimoutbound.http;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class OutcomeTest {

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource({
        "200, SUCCESS", "201, SUCCESS", "204, SUCCESS",
        "301, PERMANENT", "302, PERMANENT",
        "400, PERMANENT", "401, UNAUTHORIZED", "403, PERMANENT", "404, NOT_FOUND",
        "409, CONFLICT", "412, PRECONDITION_FAILED", "408, RETRY", "429, RETRY",
        "500, RETRY", "502, RETRY", "503, RETRY", "504, RETRY"})
    void classifiesEveryDocumentedStatus(int status, Outcome expected) {
        assertThat(Outcome.classify(status)).isEqualTo(expected);
    }
}
