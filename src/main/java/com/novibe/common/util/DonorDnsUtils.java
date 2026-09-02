package com.novibe.common.util;

import com.novibe.common.base_structures.BypassRoute;
import com.novibe.common.base_structures.DnsProfile;
import com.novibe.common.exception.UserInputException;
import com.novibe.common.security.NetworkTargetPolicy;
import org.xbill.DNS.ARecord;
import org.xbill.DNS.Address;
import org.xbill.DNS.DohResolver;
import org.xbill.DNS.Lookup;
import org.xbill.DNS.Record;
import org.xbill.DNS.Resolver;
import org.xbill.DNS.SimpleResolver;
import org.xbill.DNS.TextParseException;
import org.xbill.DNS.Type;

import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public class DonorDnsUtils {

    public static void replaceIPs(List<BypassRoute> bypassRoutes, DnsProfile dnsProfile) {
        Resolver dnsResolver = getDnsResolver(dnsProfile);
        dnsResolver.setTimeout(Duration.ofSeconds(5));
        try (ExecutorService executor = Executors.newFixedThreadPool(32)) {
            List<Future<?>> tasks = new ArrayList<>();
            for (BypassRoute bypassRoute : bypassRoutes) {
                tasks.add(executor.submit(() -> replaceIp(bypassRoute, dnsResolver)));
            }
            for (Future<?> task : tasks) task.get();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Donor DNS resolution was interrupted", exception);
        } catch (ExecutionException exception) {
            throw new IllegalStateException("Donor DNS resolution failed", exception.getCause());
        }
    }

    private static void replaceIp(BypassRoute bypassRoute, Resolver dnsResolver) {
        String donorIp = fetchDonorIp(bypassRoute.website(), dnsResolver);
        if (donorIp != null && !bypassRoute.ip().equals(donorIp)) {
            Log.common("Changed IP for %s: %s -> %s".formatted(bypassRoute.website(), bypassRoute.ip(), donorIp));
            bypassRoute.ip(donorIp);
        }
    }

    private static Resolver getDnsResolver(DnsProfile dnsProfile) {
        String dns = dnsProfile.donorDns();
        try {
            if (dns.startsWith("https://")) {
                URI uri = URI.create(dns);
                NetworkTargetPolicy.requirePublicHttps(uri);
                return new DohResolver(dns);
            }
            if (Address.isDottedQuad(dns)) {
                NetworkTargetPolicy.requirePublicAddress(InetAddress.getByName(dns));
                return new SimpleResolver(dns);
            }
            throw new UnknownHostException();
        } catch (IOException | IllegalArgumentException exception) {
            throw UserInputException.noStackTrace(
                    "Invalid DONOR_DNS value. Use a public IPv4 address or public HTTPS DoH endpoint."
            );
        }
    }

    private static String fetchDonorIp(String domain, Resolver resolver) {
        try {
            Lookup lookup = new Lookup(domain, Type.A);
            lookup.setResolver(resolver);
            Record[] records = lookup.run();
            if (records != null && records.length > 0) {
                return ((ARecord) records[0]).getAddress().getHostAddress();
            }
            return null;
        } catch (TextParseException exception) {
            Log.fail("Invalid domain address: " + domain);
            return null;
        }
    }
}
