package com.novibe.dns.cloudflare.service;

import com.novibe.common.base_structures.BypassRoute;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class GoogleAiRouteSelectorTest {
    private static final String FIRST = "193.233.112.68";
    private static final String BACKUP = "159.194.200.33";

    @Test
    void usesReachableBackupInsteadOfDiscardingItDuringDeduplication() {
        GoogleAiRouteSelector selector = new GoogleAiRouteSelector((host, ip) -> BACKUP.equals(ip));
        List<BypassRoute> selected = selector.select(List.of(
                new BypassRoute(FIRST, "gemini.google.com"),
                new BypassRoute(BACKUP, "gemini.google.com"),
                new BypassRoute(FIRST, "geminiweb-pa.clients6.google.com"),
                new BypassRoute(BACKUP, "geminiweb-pa.clients6.google.com")
        ), List.of());

        assertEquals(2, selected.size());
        assertTrue(selected.stream().allMatch(route -> BACKUP.equals(route.ip())));
    }

    @Test
    void prefersLocallyVerifiedSourceIpEvenIfRunnerCanReachOtherIps() {
        GoogleAiRouteSelector selector = new GoogleAiRouteSelector((host, ip) -> true);
        List<BypassRoute> selected = selector.select(List.of(
                new BypassRoute(FIRST, "gemini.google.com"),
                new BypassRoute(BACKUP, "gemini.google.com")
        ), List.of("8.8.8.8", BACKUP));

        assertEquals(BACKUP, selected.getFirst().ip());
    }

    @Test
    void fallsBackIfPreferredAddressDisappearsFromSource() {
        GoogleAiRouteSelector selector = new GoogleAiRouteSelector((host, ip) -> FIRST.equals(ip));
        List<BypassRoute> selected = selector.select(List.of(
                new BypassRoute(FIRST, "gemini.google.com"),
                new BypassRoute(FIRST, "ai.google.dev")
        ), List.of(BACKUP));

        assertTrue(selected.stream().allMatch(route -> FIRST.equals(route.ip())));
    }

    @Test
    void locallyVerifiedPreferenceSurvivesGeoRestrictedRunnerConnectivity() {
        GoogleAiRouteSelector selector = new GoogleAiRouteSelector((host, ip) -> false);
        List<BypassRoute> selected = selector.select(List.of(
                new BypassRoute(FIRST, "gemini.google.com"),
                new BypassRoute(BACKUP, "gemini.google.com")
        ), List.of(BACKUP));
        assertEquals(BACKUP, selected.getFirst().ip());
    }

    @Test
    void refusesPartialPlanWhenDomainHasNoReachableCandidate() {
        GoogleAiRouteSelector selector = new GoogleAiRouteSelector((host, ip) -> BACKUP.equals(ip));
        assertThrows(IllegalStateException.class, () -> selector.select(List.of(
                new BypassRoute(BACKUP, "gemini.google.com"),
                new BypassRoute(FIRST, "ai.google.dev")
        ), List.of()));
    }

    @Test
    void probesEachIpOnceUsingGeminiSniWhenAvailable() {
        Map<String, String> hosts = new ConcurrentHashMap<>();
        AtomicInteger checks = new AtomicInteger();
        GoogleAiRouteSelector selector = new GoogleAiRouteSelector((host, ip) -> {
            checks.incrementAndGet();
            hosts.put(ip, host);
            return true;
        });
        selector.select(List.of(
                new BypassRoute(BACKUP, "ai.google.dev"),
                new BypassRoute(BACKUP, "gemini.google.com"),
                new BypassRoute(BACKUP, "gemini.google.com")
        ), List.of());

        assertEquals(1, checks.get());
        assertEquals("gemini.google.com", hosts.get(BACKUP));
    }

    @Test
    void rejectsPrivateSourceAddressesBeforeProbing() {
        GoogleAiRouteSelector selector = new GoogleAiRouteSelector((host, ip) -> {
            fail("Private source addresses must not be contacted");
            return true;
        });
        assertThrows(IllegalArgumentException.class, () -> selector.select(
                List.of(new BypassRoute("127.0.0.1", "gemini.google.com")), List.of()));
    }
}
