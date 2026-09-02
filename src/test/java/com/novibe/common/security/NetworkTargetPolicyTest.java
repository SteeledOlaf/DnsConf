package com.novibe.common.security;

import org.junit.jupiter.api.Test;

import java.net.URI;

import static org.junit.jupiter.api.Assertions.assertThrows;

class NetworkTargetPolicyTest {

    @Test
    void rejectsNonHttpsAndLoopbackTargets() {
        assertThrows(Exception.class, () -> NetworkTargetPolicy.requirePublicHttps(URI.create("http://example.com/list")));
        assertThrows(Exception.class, () -> NetworkTargetPolicy.requirePublicHttps(URI.create("https://127.0.0.1/list")));
        assertThrows(Exception.class, () -> NetworkTargetPolicy.requirePublicHttps(URI.create("https://[::1]/list")));
    }
}
