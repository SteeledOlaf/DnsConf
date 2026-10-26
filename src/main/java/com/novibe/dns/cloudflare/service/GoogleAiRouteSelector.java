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
    private static final int TIMEOUT_MS = 4_000;
    private final EndpointProbe probe;

    @Autowired
    public GoogleAiRouteSelector() {
        this(GoogleAiRouteSelector::verifyTls);
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
        Map<String, Boolean> healthy = checkEndpoints(probeHosts);
        List<BypassRoute> selected = new ArrayList<>();
        for (Map.Entry<String, Set<String>> entry : candidates.entrySet()) {
            String chosen = preferredIps.stream().filter(entry.getValue()::contains).findFirst().orElse(null);
            if (chosen == null) {
                chosen = entry.getValue().stream().filter(ip -> Boolean.TRUE.equals(healthy.get(ip)))
                        .findFirst().orElseThrow(() -> new IllegalStateException(
                                "No TLS-verified Google AI endpoint for " + entry.getKey()
                                        + "; existing Cloudflare configuration was preserved."));
            }
            selected.add(new BypassRoute(chosen, entry.getKey()));
        }
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (BypassRoute route : selected) counts.merge(route.ip(), 1, Integer::sum);
        Log.common("Google AI selected endpoints (IP=domains): " + counts);
        if (selected.stream().anyMatch(route -> !Boolean.TRUE.equals(healthy.get(route.ip())))) {
            Log.common("Google AI uses explicitly configured locally verified source IPs despite failed runner probes. "
                    + "GitHub Actions and client connectivity differ; recheck these preferences from the client network.");
        }
        return List.copyOf(selected);
    }

    private Map<String, Boolean> checkEndpoints(Map<String, String> hosts) {
        Map<String, Boolean> result = new LinkedHashMap<>();
        try (var executor = Executors.newFixedThreadPool(8)) {
            Map<String, Future<Boolean>> checks = new LinkedHashMap<>();
            hosts.forEach((ip, host) -> checks.put(ip, executor.submit(() -> probe.reachable(host, ip))));
            for (Map.Entry<String, Future<Boolean>> check : checks.entrySet()) {
                boolean reachable = check.getValue().get();
                result.put(check.getKey(), reachable);
                Log.common("Google AI TLS probe: %s (%s): %s".formatted(
                        check.getKey(), hosts.get(check.getKey()), reachable ? "OK" : "FAILED"));
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

    private static boolean verifyTls(String host, String ip) {
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
                return true;
            }
        } catch (IOException | IllegalArgumentException exception) {
            Log.common("Google AI TLS probe failed for %s at %s: %s".formatted(
                    host, ip, exception.getClass().getSimpleName()));
            return false;
        }
    }

    @FunctionalInterface
    interface EndpointProbe {
        boolean reachable(String host, String ip);
    }
}
