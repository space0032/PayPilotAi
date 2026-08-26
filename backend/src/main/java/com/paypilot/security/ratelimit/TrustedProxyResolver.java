package com.paypilot.security.ratelimit;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Resolves the client address that rate limiting keys on, honouring
 * X-Forwarded-For ONLY when the socket peer is a configured trusted
 * proxy. A chain "spoofed, client, proxy1" appended hop-by-hop by
 * honest proxies carries lies at the left; the only entries we can
 * believe are those our own proxies wrote - so we walk right-to-left,
 * skip hops we trust, and take the first untrusted address. Spoofed
 * leftmost values never survive that walk.
 *
 * CIDR support covers IPv4 ranges; IPv6 is exact-match (loopback and
 * link-local peers are the realistic proxy case locally).
 */
@Component
public class TrustedProxyResolver {

    private static final Logger log = LoggerFactory.getLogger(TrustedProxyResolver.class);
    private static final Pattern LITERAL_IP = Pattern.compile("[0-9a-fA-F:.]+");

    private final List<CidrRange> ranges;

    public TrustedProxyResolver(TrustedProxyProperties properties) {
        this.ranges = properties.cidrs().stream()
                .map(TrustedProxyResolver::parse)
                .toList();
    }

    /** True if this socket peer may set X-Forwarded-For on a client's behalf. */
    public boolean isTrusted(String ip) {
        String normalized = normalize(ip);
        return normalized != null && ranges.stream().anyMatch(r -> r.contains(normalized));
    }

    /**
     * The address to key per-client state on: the socket peer unless it is
     * a trusted proxy, in which case the nearest untrusted XFF entry.
     */
    public String clientIp(HttpServletRequest request) {
        String remote = normalize(request.getRemoteAddr());
        if (!isTrusted(remote)) {
            return remote;
        }
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded == null || forwarded.isBlank()) {
            return remote;
        }
        String[] hops = forwarded.split(",");
        for (int i = hops.length - 1; i >= 0; i--) {
            String candidate = normalize(hops[i]);
            if (candidate != null && !isTrusted(candidate)) {
                return candidate;
            }
        }
        // Every claimed address is itself trusted - refuse to guess.
        log.warn("X-Forwarded-For chain '{}' contains no untrusted address", forwarded);
        return remote;
    }

    /** IPv4-mapped IPv6 (::ffff:a.b.c.d) collapses to its IPv4 form. */
    private static String normalize(String raw) {
        if (raw == null) {
            return null;
        }
        String ip = raw.trim();
        if (ip.startsWith("::ffff:") && ip.contains(".")) {
            ip = ip.substring(7);
        } else if (ip.startsWith("/")) {
            ip = ip.substring(1);
        }
        return ip.isEmpty() ? null : ip;
    }

    private static CidrRange parse(String entry) {
        String value = entry.trim();
        int slash = value.indexOf('/');
        try {
            InetAddress addr = InetAddress.getByName(slash < 0 ? value : value.substring(0, slash));
            int bits = slash < 0 ? (addr.getAddress().length == 4 ? 32 : 128)
                    : Integer.parseInt(value.substring(slash + 1));
            return new CidrRange(addr, bits);
        } catch (UnknownHostException e) {
            throw new IllegalStateException("Invalid trusted-proxy entry '" + entry + "'", e);
        }
    }

    private record CidrRange(InetAddress network, int prefixBits) {

        boolean contains(String ip) {
            if (!LITERAL_IP.matcher(ip).matches()) {
                return false; // never resolve hostnames - DNS is not trust
            }
            InetAddress candidate;
            try {
                candidate = InetAddress.getByName(ip);
            } catch (UnknownHostException e) {
                return false;
            }
            if (candidate.getAddress().length != network.getAddress().length) {
                return false;
            }
            byte[] a = network.getAddress();
            byte[] b = candidate.getAddress();
            int fullBytes = prefixBits / 8;
            for (int i = 0; i < fullBytes; i++) {
                if (a[i] != b[i]) {
                    return false;
                }
            }
            int remainderBits = prefixBits % 8;
            if (remainderBits == 0) {
                return true;
            }
            int mask = 0xFF << (8 - remainderBits);
            return (a[fullBytes] & mask) == (b[fullBytes] & mask);
        }
    }
}
