/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 Shebang Labs
 */
package io.shebanglabs.scimoutbound.http;

import io.shebanglabs.scimoutbound.scim.ScimSchemas;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import org.apache.http.Header;
import org.apache.http.HttpEntity;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpRequestBase;
import org.apache.http.client.methods.RequestBuilder;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.jboss.logging.Logger;

/**
 * Low-level transport for SCIM requests. Wraps a (usually Keycloak-provided, pooled) Apache
 * HttpClient and applies per-request safety settings: timeouts, no redirects, a response size
 * cap. Logging only ever contains method, path (without query) and status.
 */
public class ScimHttp {

    private static final Logger LOG = Logger.getLogger(ScimHttp.class);
    private static final ContentType SCIM_JSON = ContentType.create(ScimSchemas.MEDIA_TYPE, StandardCharsets.UTF_8);
    static final String USER_AGENT = "scim-outbound-for-keycloak";

    private final CloseableHttpClient client;
    private final RequestConfig requestConfig;
    private final long maxResponseBytes;
    private final Clock clock;

    public ScimHttp(CloseableHttpClient client, Duration connectTimeout, Duration readTimeout,
            long maxResponseBytes, Clock clock) {
        this.client = client;
        this.maxResponseBytes = maxResponseBytes;
        this.clock = clock;
        this.requestConfig = RequestConfig.custom()
                .setConnectTimeout((int) connectTimeout.toMillis())
                .setConnectionRequestTimeout((int) connectTimeout.toMillis())
                .setSocketTimeout((int) readTimeout.toMillis())
                .setRedirectsEnabled(false)
                .setCircularRedirectsAllowed(false)
                .build();
    }

    /**
     * Sends a request with a JSON body ({@code null} for none) and extra headers.
     *
     * @throws ScimTransportException on network errors or oversized responses
     */
    public ScimResponse send(String method, URI uri, String jsonBody, Map<String, String> headers)
            throws ScimTransportException {
        HttpEntity entity = jsonBody == null ? null : new StringEntity(jsonBody, SCIM_JSON);
        return execute(method, uri, entity, headers, ScimSchemas.MEDIA_TYPE + ", application/json");
    }

    /** POSTs an {@code application/x-www-form-urlencoded} body, used for OAuth token requests. */
    public ScimResponse sendForm(URI uri, String form, Map<String, String> headers) throws ScimTransportException {
        HttpEntity entity = new StringEntity(form, ContentType.APPLICATION_FORM_URLENCODED);
        return execute("POST", uri, entity, headers, "application/json");
    }

    private ScimResponse execute(String method, URI uri, HttpEntity entity, Map<String, String> headers,
            String accept) throws ScimTransportException {
        RequestBuilder builder = RequestBuilder.create(method)
                .setUri(uri)
                .setConfig(requestConfig)
                .addHeader("Accept", accept)
                .addHeader("User-Agent", USER_AGENT);
        headers.forEach(builder::addHeader);
        if (entity != null) {
            builder.setEntity(entity);
        }
        HttpRequestBase request = (HttpRequestBase) builder.build();
        long started = System.nanoTime();
        try (CloseableHttpResponse response = client.execute(request)) {
            int status = response.getStatusLine().getStatusCode();
            String body = readBounded(response.getEntity());
            Header retry = response.getFirstHeader("Retry-After");
            Optional<Duration> retryAfter = RetryAfter.parse(retry == null ? null : retry.getValue(), clock);
            LOG.debugf("SCIM %s %s -> %d in %d ms", method, uri.getRawPath(), status,
                    (System.nanoTime() - started) / 1_000_000);
            return new ScimResponse(status, body, retryAfter);
        } catch (ResponseTooLargeException e) {
            request.abort();
            throw new ScimTransportException(e.getMessage(), null, true);
        } catch (IOException e) {
            LOG.debugf("SCIM %s %s failed: %s", method, uri.getRawPath(), e.getClass().getSimpleName());
            throw new ScimTransportException(describe(e), e, false);
        }
    }

    private String readBounded(HttpEntity entity) throws IOException {
        if (entity == null) {
            return "";
        }
        if (entity.getContentLength() > maxResponseBytes) {
            throw new ResponseTooLargeException(maxResponseBytes);
        }
        try (InputStream in = entity.getContent()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            long total = 0;
            int read;
            while ((read = in.read(buffer)) != -1) {
                total += read;
                if (total > maxResponseBytes) {
                    throw new ResponseTooLargeException(maxResponseBytes);
                }
                out.write(buffer, 0, read);
            }
            return out.toString(StandardCharsets.UTF_8);
        }
    }

    private static String describe(IOException e) {
        String type = e.getClass().getSimpleName();
        return switch (type) {
            case "ConnectTimeoutException", "HttpHostConnectException" -> "connection failed (" + type + ")";
            case "SocketTimeoutException" -> "read timed out";
            case "SSLHandshakeException", "SSLPeerUnverifiedException" -> "TLS verification failed (" + type + ")";
            case "UnknownHostException" -> "host cannot be resolved";
            default -> "I/O error (" + type + ")";
        };
    }

    private static final class ResponseTooLargeException extends IOException {
        ResponseTooLargeException(long max) {
            super("response exceeds " + max + " bytes");
        }
    }
}
