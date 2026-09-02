package com.novibe.common.config;

import com.novibe.common.exception.UserInputException;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class AppSettingsTest {

    @Test
    void parsesSafetyFlagsAndRedactsSecrets() {
        Map<String, String> env = new HashMap<>(Map.of(
                "DNS", "CLOUDFLARE",
                "CLIENT_ID", "account",
                "AUTH_SECRET", "secret",
                "ALLOW_CLEAR", "true",
                "DRY_RUN", "true"
        ));

        AppSettings settings = AppSettings.from(env);

        assertTrue(settings.allowClear());
        assertTrue(settings.dryRun());
        assertFalse(settings.toString().contains("secret"));
        assertFalse(settings.toString().contains("account"));
    }

    @Test
    void requiresCredentials() {
        assertThrows(UserInputException.class, () -> AppSettings.from(Map.of("DNS", "NEXTDNS")));
    }
}
