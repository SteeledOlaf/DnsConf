package com.novibe.dns.cloudflare.service;

import com.novibe.common.base_structures.BypassRoute;
import com.novibe.common.base_structures.DnsProfile;
import com.novibe.common.config.AppSettings;
import com.novibe.common.data_sources.ExcludeRedirectSettingsLoader;
import com.novibe.common.service.ExcludeRedirectCheckService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class CloudflareListPlannerTest {

    private CloudflareListPlanner planner() {
        AppSettings settings = new AppSettings(
                "cloudflare", "account", "secret", null, null, "skip.example", null,
                false, false, "owner"
        );
        DnsProfile profile = new DnsProfile("CLOUDFLARE", "account", "secret", 1, null);
        OwnershipMarker marker = new OwnershipMarker(profile, settings, "11111111-1111-4111-8111-111111111111");
        ExcludeRedirectCheckService excludes = new ExcludeRedirectCheckService(
                new ExcludeRedirectSettingsLoader(settings)
        );
        return new CloudflareListPlanner(excludes, marker);
    }

    @Test
    void normalizesBeforeBuildingRequests() {
        CloudflareListPlanner planner = planner();
        assertEquals(List.of("example.com"), planner.normalizeBlocks(List.of(
                "*.Example.com", "https://example.com/path", "invalid", "127.0.0.1"
        )));
    }

    @Test
    void removesExcludedRedirectsAndKeepsFirstDomainValue() {
        List<BypassRoute> routes = planner().normalizeRedirects(List.of(
                new BypassRoute("1.1.1.1", "skip.example"),
                new BypassRoute("2.2.2.2", "www.example.com"),
                new BypassRoute("3.3.3.3", "example.com")
        ));
        assertEquals(1, routes.size());
        assertEquals("2.2.2.2", routes.getFirst().ip());
    }

    @Test
    void groupsRedirectDomainsByIpWithoutAllocatingGatewayLists() {
        Map<String, List<String>> grouped = planner().redirectDomainsByIp(List.of(
                new BypassRoute("2.2.2.2", "two.example"),
                new BypassRoute("1.1.1.1", "one.example"),
                new BypassRoute("1.1.1.1", "another.example")
        ));

        assertEquals(List.of("1.1.1.1", "2.2.2.2"), grouped.keySet().stream().toList());
        assertEquals(List.of("one.example", "another.example"), grouped.get("1.1.1.1"));
    }
}
