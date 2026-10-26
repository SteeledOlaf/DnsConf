package com.novibe.common.data_sources;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class HostsOverrideListsLoaderTest {

    @Test
    void preservesDistinctIpCandidatesForTheSameDomainWhileRemovingExactDuplicates() {
        var routes = new HostsOverrideListsLoader().parseWebsites(List.of(
                "193.233.112.68 gemini.google.com",
                "159.194.200.33 gemini.google.com",
                "159.194.200.33 gemini.google.com"
        ));
        assertEquals(List.of("193.233.112.68", "159.194.200.33"),
                routes.stream().map(com.novibe.common.base_structures.BypassRoute::ip).toList());
    }

    @Test
    void blockLoaderStillDeduplicatesByDomain() {
        assertEquals(List.of("blocked.example"), new HostsBlockListsLoader().parseWebsites(List.of(
                "0.0.0.0 blocked.example", "127.0.0.1 blocked.example", "blocked.example"
        )));
    }

    @Test
    void extractsOnlyDomainsFromNamedPrioritySection() {
        Set<String> domains = HostsOverrideListsLoader.domainsInSection(List.of(
                "# Other service",
                "192.0.2.1 before.example",
                "# Google AI",
                "192.0.2.2 Gemini.Google.com",
                "192.0.2.3 generativelanguage.googleapis.com # API",
                "# Grok",
                "192.0.2.4 after.example"
        ), "Google AI");

        assertEquals(Set.of("gemini.google.com", "generativelanguage.googleapis.com"), domains);
    }

    @Test
    void extractsDomainsFromSupportedGoogleAiSectionAliasesOnly() {
        Set<String> domains = HostsOverrideListsLoader.domainsInSections(List.of(
                "# Adobe AI",
                "192.0.2.1 firefly.example",
                "# Google Gemini AI",
                "192.0.2.2 gemini.google.com",
                "# Grok AI",
                "192.0.2.3 grok.example"
        ), Set.of("Google AI", "Google Gemini AI"));

        assertEquals(Set.of("gemini.google.com"), domains);
    }
}
