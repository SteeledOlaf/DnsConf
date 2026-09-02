package com.novibe.dns.next_dns.service;

import com.novibe.common.base_structures.BypassRoute;
import com.novibe.common.service.ExcludeRedirectCheckService;
import com.novibe.common.util.Log;
import com.novibe.dns.next_dns.http.NextDnsRateLimitedApiProcessor;
import com.novibe.dns.next_dns.http.NextDnsRewriteClient;
import com.novibe.dns.next_dns.http.dto.request.CreateRewriteDto;
import com.novibe.dns.next_dns.http.dto.response.rewrite.RewriteDto;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class NextDnsRewriteService {

    private final NextDnsRewriteClient nextDnsRewriteClient;
    private final ExcludeRedirectCheckService excludeRedirectCheckService;

    public void reconcile(List<BypassRoute> overrides) {
        Map<String, CreateRewriteDto> desired = buildDesired(overrides);
        List<RewriteDto> existing = getExistingRewrites();
        Map<String, RewriteDto> existingByDomain = existing.stream()
                .collect(Collectors.toMap(RewriteDto::name, Function.identity(), (first, ignored) -> first));

        List<String> excludedIds = existing.stream()
                .filter(rewrite -> excludeRedirectCheckService.shouldExclude(rewrite.name()))
                .map(RewriteDto::id)
                .toList();
        NextDnsRateLimitedApiProcessor.callApi(excludedIds, nextDnsRewriteClient::deleteRewriteById);
        desired.keySet().removeIf(excludeRedirectCheckService::shouldExclude);

        List<CreateRewriteDto> additions = new ArrayList<>();
        for (CreateRewriteDto request : desired.values()) {
            RewriteDto current = existingByDomain.get(request.name());
            if (current == null) {
                additions.add(request);
            } else if (!current.content().equals(request.content())) {
                replaceWithRollback(current, request);
            }
        }
        NextDnsRateLimitedApiProcessor.callApi(additions, nextDnsRewriteClient::saveRewrite);
    }

    public Map<String, CreateRewriteDto> buildDesired(List<BypassRoute> overrides) {
        Map<String, CreateRewriteDto> desired = new LinkedHashMap<>();
        overrides.forEach(route -> desired.putIfAbsent(
                route.website(), new CreateRewriteDto(route.website(), route.ip())
        ));
        return desired;
    }

    private void replaceWithRollback(RewriteDto current, CreateRewriteDto replacement) {
        Log.io("Replacing changed NextDNS rewrite: " + current.name());
        NextDnsRateLimitedApiProcessor.callApi(List.of(current.id()), nextDnsRewriteClient::deleteRewriteById);
        try {
            NextDnsRateLimitedApiProcessor.callApi(List.of(replacement), nextDnsRewriteClient::saveRewrite);
        } catch (RuntimeException replacementFailure) {
            try {
                CreateRewriteDto rollback = new CreateRewriteDto(current.name(), current.content());
                NextDnsRateLimitedApiProcessor.callApi(List.of(rollback), nextDnsRewriteClient::saveRewrite);
            } catch (RuntimeException rollbackFailure) {
                replacementFailure.addSuppressed(rollbackFailure);
            }
            throw replacementFailure;
        }
    }

    public List<RewriteDto> getExistingRewrites() {
        Log.io("Fetching existing rewrites from NextDNS");
        return nextDnsRewriteClient.fetchRewrites();
    }

    public void removeAll() {
        List<String> ids = getExistingRewrites().stream().map(RewriteDto::id).toList();
        Log.io("Removing rewrites from NextDNS");
        NextDnsRateLimitedApiProcessor.callApi(ids, nextDnsRewriteClient::deleteRewriteById);
    }
}
