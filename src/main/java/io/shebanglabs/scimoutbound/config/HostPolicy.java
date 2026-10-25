/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 Shebang Labs
 */
package io.shebanglabs.scimoutbound.config;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Locale;

/**
 * Guards outgoing requests against server-side request forgery. A realm admin can only point a
 * target at URLs the operator permits:
 * <ul>
 *   <li>{@code https} only, unless {@code allow-http} is set;</li>
 *   <li>no user-info in the URL;</li>
 *   <li>the host must match the operator allowlist when one is configured;</li>
 *   <li>hosts that are not explicitly allowlisted must not resolve to loopback, private,
 *       link-local (including cloud metadata) or other non-public addresses, unless
 *       {@code allow-private-addresses} is set.</li>
 * </ul>
 */
public class HostPolicy {

    /** Resolves host names; replaceable in tests. */
    @FunctionalInterface
    public interface Resolver {
        InetAddress[] resolve(String host) throws UnknownHostException;
    }

    private final List<String> allowedHosts;
    private final boolean allowHttp;
    private final boolean allowPrivateAddresses;
    private final Resolver resolver;

    public HostPolicy(ServerConfig config) {
        this(config, InetAddress::getAllByName);
    }

    public HostPolicy(ServerConfig config, Resolver resolver) {
        this.allowedHosts = config.allowedHosts();
        this.allowHttp = config.allowHttp();
        this.allowPrivateAddresses = config.allowPrivateAddresses();
        this.resolver = resolver;
    }

    /**
     * Parses and checks a URL.
     *
     * @return the normalised URI
     * @throws InvalidConfigurationException when the URL is not permitted
     */
    public URI check(String url) {
        if (url == null || url.isBlank()) {
            throw new InvalidConfigurationException("URL is required");
        }
        URI uri;
        try {
            uri = new URI(url.trim()).normalize();
        } catch (URISyntaxException e) {
            throw new InvalidConfigurationException("URL is not valid");
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("https") && !(allowHttp && scheme.equals("http"))) {
            throw new InvalidConfigurationException(allowHttp
                    ? "URL must use https or http"
                    : "URL must use https (plain http is disabled on this server)");
        }
        if (uri.getRawUserInfo() != null) {
            throw new InvalidConfigurationException("URL must not contain credentials");
        }
        if (uri.getRawQuery() != null || uri.getRawFragment() != null) {
            throw new InvalidConfigurationException("URL must not contain a query or fragment");
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new InvalidConfigurationException("URL must contain a host");
        }
        host = host.toLowerCase(Locale.ROOT);

        boolean explicitlyAllowed = matchesAllowlist(host);
        if (!allowedHosts.isEmpty() && !explicitlyAllowed) {
            throw new InvalidConfigurationException("host " + host + " is not in the server allowlist");
        }
        if (!explicitlyAllowed && !allowPrivateAddresses) {
            checkPublicAddress(host);
        }
        return uri;
    }

    boolean matchesAllowlist(String host) {
        for (String entry : allowedHosts) {
            if (entry.startsWith("*.")) {
                String suffix = entry.substring(1);
                if (host.endsWith(suffix) && host.length() > suffix.length()) {
                    return true;
                }
            } else if (entry.equals(host)) {
                return true;
            }
        }
        return false;
    }

    private void checkPublicAddress(String host) {
        String bare = host.startsWith("[") && host.endsWith("]") ? host.substring(1, host.length() - 1) : host;
        InetAddress[] addresses;
        try {
            addresses = resolver.resolve(bare);
        } catch (UnknownHostException e) {
            throw new InvalidConfigurationException("host " + host + " cannot be resolved");
        }
        for (InetAddress address : addresses) {
            if (isNonPublic(address)) {
                throw new InvalidConfigurationException("host " + host
                        + " resolves to a private or local address, which this server does not allow");
            }
        }
    }

    static boolean isNonPublic(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                || address.isSiteLocalAddress() || address.isMulticastAddress()) {
            return true;
        }
        byte[] b = address.getAddress();
        if (address instanceof Inet6Address) {
            // fc00::/7 unique local addresses
            return (b[0] & 0xfe) == 0xfc;
        }
        int first = b[0] & 0xff;
        int second = b[1] & 0xff;
        // 100.64.0.0/10 carrier-grade NAT, 0.0.0.0/8, 192.0.0.0/24, 198.18.0.0/15 benchmark
        return (first == 100 && second >= 64 && second <= 127)
                || first == 0
                || (first == 192 && second == 0 && (b[2] & 0xff) == 0)
                || (first == 198 && (second == 18 || second == 19));
    }
}
