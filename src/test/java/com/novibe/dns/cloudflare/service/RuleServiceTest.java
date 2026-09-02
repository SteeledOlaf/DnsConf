package com.novibe.dns.cloudflare.service;

import com.novibe.common.base_structures.DnsProfile;
import com.novibe.common.config.AppSettings;
import com.novibe.dns.cloudflare.http.CloudflareRuleClient;
import com.novibe.dns.cloudflare.http.dto.request.CreateRuleRequest;
import com.novibe.dns.cloudflare.http.dto.response.rule.GatewayRuleDto;
import com.novibe.dns.cloudflare.http.dto.response.rule.SingleRuleApiResponse;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuleServiceTest {

    @Test
    void buildsInlineDomainExpressionForRedirects() {
        List<String> expressions = RuleService.makeInlineTrafficExpressions(List.of(
                "example.com", "sub.example.net"
        ));

        assertEquals(1, expressions.size());
        assertEquals(
                "any(dns.domains[*] == \"example.com\") or any(dns.domains[*] == \"sub.example.net\")",
                expressions.getFirst()
        );
    }

    @Test
    void splitsVeryLargeInlineExpressionsBelowCloudflareLimit() {
        List<String> domains = java.util.stream.IntStream.range(0, 4_000)
                .mapToObj(index -> "domain-" + index + ".example.com")
                .toList();

        List<String> expressions = RuleService.makeInlineTrafficExpressions(domains);

        assertTrue(expressions.size() > 1);
        assertTrue(expressions.stream().allMatch(expression -> expression.length() <= 120_000));
        assertTrue(expressions.stream().allMatch(expression -> occurrences(expression, "any(dns.domains[*]") <= 100));
    }

    @Test
    void splitsAtCloudflareOneHundredValuePolicyLimit() {
        List<String> domains = java.util.stream.IntStream.range(0, 101)
                .mapToObj(index -> "domain-" + index + ".example.com")
                .toList();

        List<String> expressions = RuleService.makeInlineTrafficExpressions(domains);

        assertEquals(2, expressions.size());
        assertEquals(100, occurrences(expressions.getFirst(), "any(dns.domains[*]"));
        assertEquals(1, occurrences(expressions.getLast(), "any(dns.domains[*]"));
    }

    @Test
    void rollsBackEarlierPartsWhenCreatingAChunkedOverrideFails() {
        FailingRuleClient client = new FailingRuleClient();
        RuleService service = new RuleService(client, ownershipMarker());
        List<String> domains = java.util.stream.IntStream.range(0, 101)
                .mapToObj(index -> "domain-" + index + ".example.com")
                .toList();

        assertThrows(IllegalStateException.class, () -> service.createOverrideRules(
                domains,
                "192.0.2.1",
                RulePrecedenceCounter.providePrecedenceCounter(List.of())
        ));

        assertEquals(List.of("created-rule-1"), client.removedRuleIds);
    }

    private static OwnershipMarker ownershipMarker() {
        AppSettings settings = new AppSettings(
                "cloudflare", "account", "secret", null, null, null, null,
                false, false, "owner"
        );
        DnsProfile profile = new DnsProfile("CLOUDFLARE", "account", "secret", 1, null);
        return new OwnershipMarker(profile, settings, "11111111-1111-4111-8111-111111111111");
    }

    private static int occurrences(String value, String token) {
        return (value.length() - value.replace(token, "").length()) / token.length();
    }

    private static final class FailingRuleClient extends CloudflareRuleClient {
        private final List<String> removedRuleIds = new ArrayList<>();
        private int createCalls;

        private FailingRuleClient() {
            super(null);
        }

        @Override
        public SingleRuleApiResponse createRule(CreateRuleRequest rule) {
            createCalls++;
            SingleRuleApiResponse response = new SingleRuleApiResponse();
            if (createCalls == 1) {
                GatewayRuleDto created = new GatewayRuleDto();
                created.setId("created-rule-1");
                response.setResult(created);
                response.setSuccess(true);
            }
            return response;
        }

        @Override
        public SingleRuleApiResponse removeRuleById(String id) {
            removedRuleIds.add(id);
            SingleRuleApiResponse response = new SingleRuleApiResponse();
            response.setSuccess(true);
            return response;
        }
    }
}
