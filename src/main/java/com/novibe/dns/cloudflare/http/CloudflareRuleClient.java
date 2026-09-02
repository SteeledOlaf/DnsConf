package com.novibe.dns.cloudflare.http;

import com.novibe.dns.cloudflare.http.dto.request.CreateRuleRequest;
import com.novibe.dns.cloudflare.http.dto.response.rule.GatewayRuleDto;
import com.novibe.dns.cloudflare.http.dto.response.rule.MultiRuleApiResponse;
import com.novibe.dns.cloudflare.http.dto.response.rule.SingleRuleApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

@Component
@RequiredArgsConstructor
public class CloudflareRuleClient {

    private static final String PATH = "/rules";
    private final RequestCloudflare requestCloudflare;

    public SingleRuleApiResponse createRule(CreateRuleRequest rule) {
        return requestCloudflare.post(PATH, rule, SingleRuleApiResponse.class);
    }

    public SingleRuleApiResponse updateRule(String id, CreateRuleRequest rule) {
        return requestCloudflare.put(PATH + "/" + id, rule, SingleRuleApiResponse.class);
    }

    public SingleRuleApiResponse removeRuleById(String id) {
        return requestCloudflare.delete(PATH + "/" + id, SingleRuleApiResponse.class);
    }

    public List<GatewayRuleDto> getRules() {
        List<GatewayRuleDto> rules = new ArrayList<>();
        for (int page = 1; ; page++) {
            MultiRuleApiResponse response = requestCloudflare.get(
                    PATH + "?page=" + page + "&per_page=50", MultiRuleApiResponse.class
            );
            if (response == null || !response.isSuccess()) {
                throw new IllegalStateException("Failed to list Cloudflare Gateway rules: "
                        + (response == null ? "empty response" : response.getErrors()));
            }
            List<GatewayRuleDto> current = Objects.requireNonNullElse(response.getResult(), List.of());
            rules.addAll(current);
            if (response.getResultInfo() == null || current.isEmpty()) {
                return List.copyOf(rules);
            }
            int total = response.getResultInfo().getTotalCount();
            if (total > 0) {
                if (rules.size() >= total) return List.copyOf(rules);
            } else if (response.getResultInfo().getPerPage() <= 0
                    || current.size() < response.getResultInfo().getPerPage()) {
                return List.copyOf(rules);
            }
        }
    }
}
