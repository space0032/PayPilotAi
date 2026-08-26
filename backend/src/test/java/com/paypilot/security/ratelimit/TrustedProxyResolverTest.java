package com.paypilot.security.ratelimit;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TrustedProxyResolverTest {

    private TrustedProxyResolver resolver(List<String> cidrs) {
        return new TrustedProxyResolver(new TrustedProxyProperties(cidrs));
    }

    @Test
    void directExposure_ignoresXff() {
        TrustedProxyResolver resolver = resolver(List.of());

        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setRemoteAddr("192.168.1.5");
        req.addHeader("X-Forwarded-For", "203.0.113.55, 192.168.1.5");

        assertThat(resolver.clientIp(req)).isEqualTo("192.168.1.5");
        assertThat(resolver.isTrusted("192.168.1.5")).isFalse();
    }

    @Test
    void singleTrustedProxy_takesUntrustedXffEntry() {
        TrustedProxyResolver resolver = resolver(List.of("10.0.0.1"));

        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setRemoteAddr("10.0.0.1");
        req.addHeader("X-Forwarded-For", "  203.0.113.7 , 10.0.0.1");

        assertThat(resolver.clientIp(req)).isEqualTo("203.0.113.7");
    }

    @Test
    void multipleTrustedHops_walksRightToSkippingTrusted() {
        // chain: client=1.2.3.4, edge-proxy, our proxy, some weird thing
        // XFF as seen: client, hop1(our proxy), hop2(edge proxy)
        TrustedProxyResolver resolver = resolver(List.of("192.168.0.1/16"));

        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setRemoteAddr("192.168.0.1");
        req.addHeader("X-Forwarded-For", "1.2.3.4, 10.0.0.99, 192.168.0.1");

        // Rightmost hop (192.168.0.1) is trusted; skip it.
        // Next (10.0.0.99) is outside the /16 and untrusted.
        assertThat(resolver.clientIp(req)).isEqualTo("10.0.0.99");
    }

    @Test
    void spoofedLeftmostEntryDoesNotEvadeLimit() {
        // Attacker sets XFF to "attacker, victim"
        TrustedProxyResolver resolver = resolver(List.of("10.0.0.1"));

        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setRemoteAddr("10.0.0.1");
        req.addHeader("X-Forwarded-For", "999.999.999.999, 10.0.0.1");

        // 999... is leftmost but it is untrusted and walked-from-right:
        // rightmost is trusted -> skip; next = 999... untrusted -> returned
        assertThat(resolver.clientIp(req)).isEqualTo("999.999.999.999");
    }

    @Test
    void cidrRangeMatchesCorrectly() {
        TrustedProxyResolver resolver = resolver(List.of("172.16.0.0/12"));

        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setRemoteAddr("172.20.30.40");
        req.addHeader("X-Forwarded-For", "1.1.1.1");

        assertThat(resolver.isTrusted("172.20.30.40")).isTrue();
        assertThat(resolver.clientIp(req)).isEqualTo("1.1.1.1");
        assertThat(resolver.isTrusted("10.0.0.1")).isFalse();
    }

    @Test
    void ipv6LoopbackIsTrustedWhenCidrCoversIt() {
        TrustedProxyResolver resolver = resolver(List.of("::1"));

        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setRemoteAddr("::1");
        req.addHeader("X-Forwarded-For", "2001:db8::1");

        assertThat(resolver.isTrusted("::1")).isTrue();
        assertThat(resolver.clientIp(req)).isEqualTo("2001:db8::1");
    }

    @Test
    void ipv4MappedIPv6_collapsesToNativeForm() {
        TrustedProxyResolver resolver = resolver(List.of("10.0.0.1"));

        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setRemoteAddr("::ffff:10.0.0.1");
        req.addHeader("X-Forwarded-For", "5.5.5.5");

        assertThat(resolver.clientIp(req)).isEqualTo("5.5.5.5");
    }
}
