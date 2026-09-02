package com.novibe.common.security;

import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;

public final class NetworkTargetPolicy {

    private NetworkTargetPolicy() {
    }

    public static void requirePublicHttps(URI uri) throws IOException {
        if (uri == null || !"https".equalsIgnoreCase(uri.getScheme())) {
            throw new IOException("Only HTTPS endpoints are allowed");
        }
        if (uri.getHost() == null || uri.getHost().isBlank() || uri.getUserInfo() != null) {
            throw new IOException("Endpoint must contain a valid host and no user-info");
        }
        try {
            for (InetAddress address : InetAddress.getAllByName(uri.getHost())) {
                if (isPrivate(address)) {
                    throw new IOException("Private, local, multicast and link-local endpoints are not allowed: "
                            + uri.getHost());
                }
            }
        } catch (UnknownHostException exception) {
            throw new IOException("Unable to resolve endpoint host: " + uri.getHost(), exception);
        }
    }

    public static void requirePublicAddress(InetAddress address) throws IOException {
        if (isPrivate(address)) {
            throw new IOException("Private, local, multicast and link-local addresses are not allowed");
        }
    }

    static boolean isPrivate(InetAddress address) {
        byte[] bytes = address.getAddress();
        boolean ipv6UniqueLocal = bytes.length == 16 && (bytes[0] & 0xfe) == 0xfc;
        return address.isAnyLocalAddress()
                || address.isLoopbackAddress()
                || address.isLinkLocalAddress()
                || address.isSiteLocalAddress()
                || address.isMulticastAddress()
                || ipv6UniqueLocal;
    }
}
