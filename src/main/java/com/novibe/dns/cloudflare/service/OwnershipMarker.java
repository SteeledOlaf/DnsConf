package com.novibe.dns.cloudflare.service;

import com.novibe.common.base_structures.DnsProfile;
import com.novibe.common.config.AppSettings;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.regex.Pattern;

@Service
public class OwnershipMarker {

    private static final Pattern LEGACY_SESSION = Pattern.compile(
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-5][0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}$"
    );

    private final String prefix;
    private final String description;
    private final String generation;

    public OwnershipMarker(DnsProfile profile, AppSettings settings, String sessionId) {
        String ownershipInput = profile.dnsProvider() + "\n" + profile.clientId() + "\n" + settings.ownerId();
        String fingerprint = sha256(ownershipInput).substring(0, 16);
        this.prefix = "dnsconf:v2:" + fingerprint + ":";
        this.description = prefix + sessionId;
        this.generation = sessionId.substring(0, Math.min(8, sessionId.length()));
    }

    public String description() {
        return description;
    }

    public boolean owns(String value) {
        return value != null && value.startsWith(prefix);
    }

    public String generation() {
        return generation;
    }

    public boolean isLegacySession(String value) {
        return value != null && LEGACY_SESSION.matcher(value).matches();
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))
            );
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
