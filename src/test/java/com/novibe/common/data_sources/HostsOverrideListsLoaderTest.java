package com.novibe.common.data_sources;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class HostsOverrideListsLoaderTest {

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
}
