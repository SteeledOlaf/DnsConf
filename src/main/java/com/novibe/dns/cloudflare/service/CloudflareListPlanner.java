package com.novibe.dns.cloudflare.service;

import com.novibe.common.base_structures.BypassRoute;
import com.novibe.common.service.ExcludeRedirectCheckService;
import com.novibe.common.util.Log;
import com.novibe.dns.cloudflare.http.dto.Item;
import com.novibe.dns.cloudflare.http.dto.request.CreateListRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class CloudflareListPlanner {

    public static final String BLOCK_LIST_NAME_PREFIX = "Blocked websites by script";
    public static final String OVERRIDE_LIST_NAME_PREFIX = "Override websites by script";
    private static final int CHUNK_SIZE = 1000;
    private static final Pattern DOMAIN_PATTERN = Pattern.compile(
            "^(?=.{1,253}$)(?:[a-zA-Z0-9](?:[a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?\\.)+[a-zA-Z]{2,63}$"
    );

    private final ExcludeRedirectCheckService excludeRedirectCheckService;
    private final OwnershipMarker ownershipMarker;

    public List<String> normalizeBlocks(List<String> websites) {
        Set<String> unique = new LinkedHashSet<>();
        for (String website : websites) {
            String normalized = normalizeDomain(website);
            if (normalized != null) unique.add(normalized);
        }
        return List.copyOf(unique);
    }

    public List<BypassRoute> normalizeRedirects(List<BypassRoute> routes) {
        Map<String, String> unique = new LinkedHashMap<>();
        for (BypassRoute route : routes) {
            String normalized = normalizeDomain(route.website());
            if (normalized != null && !excludeRedirectCheckService.shouldExclude(normalized)) {
                unique.putIfAbsent(normalized, route.ip());
            }
        }
        return unique.entrySet().stream()
                .map(entry -> new BypassRoute(entry.getValue(), entry.getKey()))
                .toList();
    }

    public List<CreateListRequest> blockRequests(List<String> normalizedWebsites) {
        return mapRequests(toChunks(normalizedWebsites), BLOCK_LIST_NAME_PREFIX);
    }

    public Map<String, List<CreateListRequest>> redirectRequests(List<BypassRoute> routes) {
        Map<String, List<String>> byIp = new TreeMap<>();
        for (BypassRoute route : routes) {
            byIp.computeIfAbsent(route.ip(), ignored -> new ArrayList<>()).add(route.website());
        }
        Map<String, List<CreateListRequest>> result = new LinkedHashMap<>();
        byIp.forEach((ip, domains) -> result.put(
                ip,
                mapRequests(toChunks(domains), OVERRIDE_LIST_NAME_PREFIX + " to IP " + ip)
        ));
        return result;
    }

    public boolean isManagedName(String name) {
        return name != null && (name.startsWith(BLOCK_LIST_NAME_PREFIX)
                || name.startsWith(OVERRIDE_LIST_NAME_PREFIX));
    }

    String normalizeDomain(String value) {
        if (value == null) return null;
        String domain = value.trim().toLowerCase();
        if (domain.isEmpty()) return null;
        int commentIndex = domain.indexOf('#');
        if (commentIndex >= 0) domain = domain.substring(0, commentIndex).trim();
        domain = domain.replaceFirst("^https?://", "");
        while (domain.startsWith("*.")) domain = domain.substring(2);
        int separator = firstPositive(domain.indexOf('/'), domain.indexOf('?'), domain.indexOf('#'), domain.indexOf(':'));
        if (separator >= 0) domain = domain.substring(0, separator);
        while (domain.endsWith(".")) domain = domain.substring(0, domain.length() - 1);
        if (domain.matches("^\\d{1,3}(?:\\.\\d{1,3}){3}$") || !DOMAIN_PATTERN.matcher(domain).matches()) {
            Log.fail("Skipping invalid domain: " + value);
            return null;
        }
        return domain;
    }

    private static int firstPositive(int... indexes) {
        int result = -1;
        for (int index : indexes) if (index >= 0 && (result < 0 || index < result)) result = index;
        return result;
    }

    private List<CreateListRequest> mapRequests(List<List<String>> chunks, String prefix) {
        List<CreateListRequest> requests = new ArrayList<>();
        for (int index = 0; index < chunks.size(); index++) {
            requests.add(CreateListRequest.builder()
                    .name(prefix + " " + ownershipMarker.generation() + " " + (index + 1))
                    .type("DOMAIN")
                    .items(chunks.get(index).stream().map(Item::new).toList())
                    .description(ownershipMarker.description())
                    .build());
        }
        return requests;
    }

    private static List<List<String>> toChunks(List<String> values) {
        List<List<String>> chunks = new ArrayList<>();
        for (int start = 0; start < values.size(); start += CHUNK_SIZE) {
            chunks.add(List.copyOf(values.subList(start, Math.min(start + CHUNK_SIZE, values.size()))));
        }
        return chunks;
    }
}
