package com.novibe.dns.cloudflare.service;

import com.novibe.common.base_structures.BypassRoute;
import com.novibe.common.security.NetworkTargetPolicy;
import com.novibe.common.util.Log;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** Retains all source candidates until a reachable endpoint has been selected. */
@Service
public class GoogleAiRouteSelector {
    private static final String GEMINI_HOST = "gemini.google.com";
    private static final int TIMEOUT_MS = 5_000;
    private final EndpointProbe probe;

    @Autowired
    public GoogleAiRouteSelector() {
        this(GoogleAiRouteSelector::verifyApplication);
    }

    GoogleAiRouteSelector(EndpointProbe probe) {
        this.probe = probe;
    }

    public List<BypassRoute> select(List<BypassRoute> routes, List<String> preferredIps) {
        Map<String, Set<String>> candidates = new LinkedHashMap<>();
        Map<String, String> probeHosts = new LinkedHashMap<>();
        for (BypassRoute route : routes) {
            requirePublicIp(route.ip());
            candidates.computeIfAbsent(route.website(), ignored -> new LinkedHashSet<>()).add(route.ip());
            probeHosts.putIfAbsent(route.ip(), route.website());
            // Probe each shared endpoint with Gemini's SNI when that IP advertises Gemini support.
            if (GEMINI_HOST.equals(route.website())) probeHosts.put(route.ip(), GEMINI_HOST);
        }
        if (candidates.isEmpty()) return List.of();
        Set<String> common = new LinkedHashSet<>(candidates.values().iterator().next());
        candidates.values().forEach(common::retainAll);
        if (common.isEmpty()) {
            throw new IllegalStateException("Google AI domains have no common source IP; refusing a mixed-exit configuration.");
        }
        Map<String, String> commonProbeHosts = new LinkedHashMap<>();
        for (String ip : common) commonProbeHosts.put(ip, probeHosts.get(ip));
        Map<String, EndpointStatus> healthy = checkEndpoints(commonProbeHosts);
        Set<String> ordered = new LinkedHashSet<>();
        preferredIps.stream().filter(common::contains).forEach(ordered::add);
        ordered.addAll(common);
        String chosen = ordered.stream().filter(ip -> healthy.get(ip) == EndpointStatus.HEALTHY)
                .findFirst().orElse(null);
        // An explicit client-verified preference can bridge a runner network outage,
        // but must never override an actual Google challenge or HTTP rejection.
        if (chosen == null) {
            chosen = preferredIps.stream().filter(common::contains)
                    .filter(ip -> healthy.get(ip) == EndpointStatus.UNREACHABLE).findFirst().orElse(null);
        }
        if (chosen == null) {
            throw new IllegalStateException("No acceptable common Google AI endpoint; existing Cloudflare configuration was preserved.");
        }
        List<BypassRoute> selected = new ArrayList<>();
        for (String domain : candidates.keySet()) selected.add(new BypassRoute(chosen, domain));
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (BypassRoute route : selected) counts.merge(route.ip(), 1, Integer::sum);
        Log.common("Google AI selected endpoints (IP=domains): " + counts);
        if (healthy.get(chosen) == EndpointStatus.UNREACHABLE) {
            Log.common("Google AI uses explicitly configured locally verified source IPs despite failed runner probes. "
                    + "GitHub Actions and client connectivity differ; recheck these preferences from the client network.");
        }
        return List.copyOf(selected);
    }

    private Map<String, EndpointStatus> checkEndpoints(Map<String, String> hosts) {
        Map<String, EndpointStatus> result = new LinkedHashMap<>();
        try (var executor = Executors.newFixedThreadPool(8)) {
            Map<String, Future<EndpointStatus>> checks = new LinkedHashMap<>();
            hosts.forEach((ip, host) -> checks.put(ip, executor.submit(() -> probe.reachable(host, ip))));
            for (Map.Entry<String, Future<EndpointStatus>> check : checks.entrySet()) {
                EndpointStatus reachable = check.getValue().get();
                result.put(check.getKey(), reachable);
                Log.common("Google AI application probe: %s (%s): %s".formatted(
                        check.getKey(), hosts.get(check.getKey()), reachable));
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Google AI endpoint checks interrupted", exception);
        } catch (ExecutionException exception) {
            throw new IllegalStateException("Google AI endpoint checks failed", exception.getCause());
        }
        return result;
    }

    private static void requirePublicIp(String ip) {
        try {
            NetworkTargetPolicy.requirePublicAddress(InetAddress.ofLiteral(ip));
        } catch (IOException | IllegalArgumentException exception) {
            throw new IllegalArgumentException("Google AI source contains a non-public or invalid IP", exception);
        }
    }

    private static EndpointStatus verifyApplication(String host, String ip) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(InetAddress.ofLiteral(ip), 443), TIMEOUT_MS);
            SSLSocketFactory factory = (SSLSocketFactory) SSLSocketFactory.getDefault();
            try (SSLSocket tls = (SSLSocket) factory.createSocket(socket, host, 443, true)) {
                tls.setSoTimeout(TIMEOUT_MS);
                SSLParameters parameters = tls.getSSLParameters();
                parameters.setEndpointIdentificationAlgorithm("HTTPS");
                parameters.setServerNames(List.of(new SNIHostName(host)));
                tls.setSSLParameters(parameters);
                tls.startHandshake();
                String path = GEMINI_HOST.equals(host) ? "/app" : "/";
                String request = "GET " + path + " HTTP/1.1\r\nHost: " + host
                        + "\r\nConnection: close\r\nUser-Agent: DnsConf-Health/1.0\r\n\r\n";
                tls.getOutputStream().write(request.getBytes(StandardCharsets.US_ASCII));
                byte[] prefix = tls.getInputStream().readNBytes(16_384);
                return classifyResponse(new String(prefix, StandardCharsets.ISO_8859_1));
            }
        } catch (IOException | IllegalArgumentException exception) {
            Log.common("Google AI application probe failed for %s at %s: %s".formatted(
                    host, ip, exception.getClass().getSimpleName()));
            return EndpointStatus.UNREACHABLE;
        }
    }

    static EndpointStatus classifyResponse(String response) {
        int end = response.indexOf("\r\n\r\n");
        if (end < 0) return EndpointStatus.REJECTED;
        String[] headers = response.substring(0, end).split("\r\n");
        String[] status = headers[0].split(" ", 3);
        if (status.length < 2 || !status[0].startsWith("HTTP/1.")) return EndpointStatus.REJECTED;
        String lower = response.toLowerCase(java.util.Locale.ROOT);
        if (lower.contains("/sorry/") || lower.contains("our systems have detected unusual traffic")
                || lower.contains("location=unsupported")) return EndpointStatus.REJECTED;
        if ("200".equals(status[1])) return EndpointStatus.HEALTHY;
        // Reaching the normal Google sign-in endpoint is valid; do not follow it or send cookies.
        if ("302".equals(status[1]) || "303".equals(status[1])) {
            for (String header : headers) {
                if (!header.toLowerCase(java.util.Locale.ROOT).startsWith("location:")) continue;
                try {
                    URI target = URI.create(header.substring(header.indexOf(':') + 1).strip());
                    if ("https".equals(target.getScheme()) && "accounts.google.com".equals(target.getHost())) {
                        return EndpointStatus.HEALTHY;
                    }
                } catch (IllegalArgumentException ignored) {
                    return EndpointStatus.REJECTED;
                }
            }
        }
        return EndpointStatus.REJECTED;
    }

    enum EndpointStatus { HEALTHY, UNREACHABLE, REJECTED }

    @FunctionalInterface
    interface EndpointProbe {
        EndpointStatus reachable(String host, String ip);
    }
}
