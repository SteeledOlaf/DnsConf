package com.novibe.common.service;

import com.novibe.common.config.AppSettings;
import com.novibe.common.data_sources.ExcludeRedirectSettingsLoader;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ExcludeRedirectCheckServiceTest {

    @Test
    void matchesExactDomainAndSubdomainsOnly() {
        AppSettings settings = new AppSettings(
                "cloudflare", "id", "secret", null, null, "example.com", null,
                false, false, "test"
        );
        ExcludeRedirectCheckService service = new ExcludeRedirectCheckService(
                new ExcludeRedirectSettingsLoader(settings)
        );

        assertTrue(service.shouldExclude("example.com"));
        assertTrue(service.shouldExclude("a.example.com"));
        assertFalse(service.shouldExclude("notexample.com"));
    }
}
