package com.novibe.common.util;

import com.novibe.common.base_structures.DnsProfile;
import com.novibe.common.config.AppSettings;
import com.novibe.common.exception.UserInputException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class EnvParserTest {

    @Test
    void trimsAndDropsEmptyCommaSeparatedValues() {
        assertEquals(List.of("one", "two"), EnvParser.parse(" one, ,two "));
    }

    @Test
    void expandsOneProviderAcrossProfiles() {
        AppSettings settings = new AppSettings(
                "nextdns", "id1,id2", "secret1,secret2", null, null, null,
                "-,1.1.1.1", false, false, "test"
        );

        List<DnsProfile> profiles = EnvParser.parseProfiles(settings);

        assertEquals(2, profiles.size());
        assertEquals("NEXTDNS", profiles.get(1).dnsProvider());
        assertNull(profiles.getFirst().donorDns());
    }

    @Test
    void rejectsMismatchedCredentialCounts() {
        AppSettings settings = new AppSettings(
                "nextdns", "id1,id2", "secret1", null, null, null,
                null, false, false, "test"
        );
        assertThrows(UserInputException.class, () -> EnvParser.parseProfiles(settings));
    }
}
