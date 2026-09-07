package com.novibe.dns.cloudflare;

import com.novibe.common.base_structures.BypassRoute;
import com.novibe.common.base_structures.DnsProfile;
import com.novibe.common.config.AppSettings;
import com.novibe.common.data_sources.ExcludeRedirectSettingsLoader;
import com.novibe.common.data_sources.HostsBlockListsLoader;
import com.novibe.common.data_sources.HostsOverrideListsLoader;
import com.novibe.common.service.ExcludeRedirectCheckService;
import com.novibe.dns.cloudflare.service.CloudflareListPlanner;
import com.novibe.dns.cloudflare.service.OwnershipMarker;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CloudflareTaskRunnerTest {

    @Test
    void googleAiOnlyModePlansNoBlocksOrOtherRedirects() {
        AppSettings settings = new AppSettings(
                "cloudflare", "account", "secret", "block-source", "redirect-source", null, null,
                true, false, false, "owner"
        );
        DnsProfile profile = new DnsProfile("CLOUDFLARE", "account", "secret", 1, null);
        OwnershipMarker marker = new OwnershipMarker(
                profile, settings, "11111111-1111-4111-8111-111111111111"
        );
        CloudflareListPlanner planner = new CloudflareListPlanner(
                new ExcludeRedirectCheckService(new ExcludeRedirectSettingsLoader(settings)), marker
        );
        CloudflareTaskRunner runner = new CloudflareTaskRunner(null, null, planner);
        runner.setSettings(settings);
        runner.setDnsProfile(profile);
        runner.setBlockListsLoader(new FailingBlockLoader());
        runner.setOverrideListsLoader(new FixedOverrideLoader());

        CloudflarePlan plan = runner.plan();

        assertTrue(plan.googleAiOnly());
        assertTrue(plan.clearsConfiguration());
        assertTrue(plan.blocks().isEmpty());
        assertEquals(List.of("gemini.google.com"),
                plan.redirects().stream().map(BypassRoute::website).toList());
        assertEquals(plan.priorityRedirects(), plan.redirects());
    }

    private static final class FailingBlockLoader extends HostsBlockListsLoader {
        @Override
        public List<String> fetchWebsites(List<String> urls) {
            throw new AssertionError("BLOCK sources must not be loaded in Google AI-only mode");
        }
    }

    private static final class FixedOverrideLoader extends HostsOverrideListsLoader {
        @Override
        public PrioritizedOverrides fetchWebsitesWithPrioritySection(List<String> urls, String prioritySection) {
            return new PrioritizedOverrides(List.of(
                    new BypassRoute("192.0.2.1", "gemini.google.com"),
                    new BypassRoute("192.0.2.2", "example.com")
            ), Set.of("gemini.google.com"));
        }
    }
}
