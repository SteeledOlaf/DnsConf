package com.novibe.common.config;

import com.novibe.common.exception.UserInputException;

import java.util.Map;

public record AppSettings(String dns,
                          String clientId,
                          String authSecret,
                          String block,
                          String redirect,
                          String excludeRedirect,
                          String donorDns,
                          boolean allowClear,
                          boolean dryRun,
                          String ownerId) {

    public static AppSettings fromEnvironment() {
        return from(System.getenv());
    }

    public static AppSettings from(Map<String, String> environment) {
        return new AppSettings(
                mandatory(environment, "DNS"),
                mandatory(environment, "CLIENT_ID"),
                mandatory(environment, "AUTH_SECRET"),
                environment.get("BLOCK"),
                environment.get("REDIRECT"),
                environment.get("EXCLUDE_REDIRECT"),
                environment.get("DONOR_DNS"),
                Boolean.parseBoolean(environment.get("ALLOW_CLEAR")),
                Boolean.parseBoolean(environment.get("DRY_RUN")),
                optional(environment.get("DNSCONF_OWNER_ID"), "default")
        );
    }

    private static String mandatory(Map<String, String> environment, String key) {
        String value = environment.get(key);
        if (value == null || value.isBlank()) {
            throw UserInputException.noStackTrace("Mandatory environment variable is not provided: " + key);
        }
        return value;
    }

    private static String optional(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.strip();
    }

    @Override
    public String toString() {
        return "AppSettings[dns=%s, clientId=<redacted>, authSecret=<redacted>, dryRun=%s]"
                .formatted(dns, dryRun);
    }
}
