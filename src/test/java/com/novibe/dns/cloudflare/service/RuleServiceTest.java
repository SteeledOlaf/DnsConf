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

    @Test
    void refreshesEveryOldPrioritySlotWithoutCreatingRules() {
        RecordingRuleClient client = new RecordingRuleClient(0);
        RuleService service = new RuleService(client, ownershipMarker());
        List<GatewayRuleDto> managed = List.of(
                overrideRule("priority-1", 10, "old-one.google.ai", "192.0.2.1"),
                overrideRule("priority-2", 11, "old-two.google.ai", "192.0.2.2")
        );

        RuleService.PriorityUpdateResult result = service.refreshPriorityOverrides(
                java.util.Map.of("198.51.100.7", List.of("old-one.google.ai", "old-two.google.ai")),
                managed
        );

        assertEquals(new RuleService.PriorityUpdateResult(1, 2, 0), result);
        assertEquals(2, client.updates.size());
        assertTrue(client.updates.stream().allMatch(update -> update.request().enabled()));
        assertTrue(client.updates.stream().allMatch(update ->
                update.request().ruleSettings().overrideIps().equals(List.of("198.51.100.7"))));
        assertEquals(List.of(10, 11), client.updates.stream().map(update -> update.request().precedence()).toList());
    }

    @Test
    void borrowsLowerPriorityManagedSlotWhenGoogleAiNeedsMoreRules() {
        RecordingRuleClient client = new RecordingRuleClient(0);
        RuleService service = new RuleService(client, ownershipMarker());
        List<GatewayRuleDto> managed = List.of(
                overrideRule("priority", 20, "gemini.google.com", "192.0.2.1"),
                overrideRule("lower-priority", 21, "example.net", "192.0.2.2")
        );

        RuleService.PriorityUpdateResult result = service.refreshPriorityOverrides(
                java.util.Map.of(
                        "198.51.100.1", List.of("gemini.google.com"),
                        "198.51.100.2", List.of("generativelanguage.googleapis.com")
                ),
                managed
        );

        assertEquals(new RuleService.PriorityUpdateResult(2, 2, 1), result);
        assertEquals(List.of("priority", "lower-priority"),
                client.updates.stream().map(RecordedUpdate::ruleId).toList());
    }

    @Test
    void rollsBackPrioritySlotsIfAnInPlaceUpdateFails() {
        RecordingRuleClient client = new RecordingRuleClient(2);
        RuleService service = new RuleService(client, ownershipMarker());
        List<GatewayRuleDto> managed = List.of(
                overrideRule("priority", 30, "gemini.google.com", "192.0.2.1"),
                overrideRule("lower-priority", 31, "example.net", "192.0.2.2")
        );

        assertThrows(IllegalStateException.class, () -> service.refreshPriorityOverrides(
                java.util.Map.of(
                        "198.51.100.1", List.of("gemini.google.com"),
                        "198.51.100.2", List.of("generativelanguage.googleapis.com")
                ),
                managed
        ));

        assertEquals(3, client.updates.size());
        RecordedUpdate rollback = client.updates.getLast();
        assertEquals("priority", rollback.ruleId());
        assertEquals(List.of("192.0.2.1"), rollback.request().ruleSettings().overrideIps());
        assertTrue(rollback.request().traffic().contains("gemini.google.com"));
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

    private static GatewayRuleDto overrideRule(String id, int precedence, String domain, String ip) {
        GatewayRuleDto rule = new GatewayRuleDto();
        rule.setId(id);
        rule.setName("Rules set by script old override");
        rule.setDescription("old-description");
        rule.setAction("override");
        rule.setTraffic("any(dns.domains[*] == \"" + domain + "\")");
        rule.setPrecedence(precedence);
        rule.setEnabled(true);
        GatewayRuleDto.GatewayRuleSettingsDto settings = new GatewayRuleDto.GatewayRuleSettingsDto();
        settings.setOverrideIps(List.of(ip));
        rule.setRuleSettings(settings);
        return rule;
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

    private record RecordedUpdate(String ruleId, CreateRuleRequest request) {
    }

    private static final class RecordingRuleClient extends CloudflareRuleClient {
        private final List<RecordedUpdate> updates = new ArrayList<>();
        private final int failOnCall;
        private int updateCalls;

        private RecordingRuleClient(int failOnCall) {
            super(null);
            this.failOnCall = failOnCall;
        }

        @Override
        public SingleRuleApiResponse updateRule(String id, CreateRuleRequest rule) {
            updates.add(new RecordedUpdate(id, rule));
            updateCalls++;
            SingleRuleApiResponse response = new SingleRuleApiResponse();
            response.setSuccess(updateCalls != failOnCall);
            return response;
        }
    }
}
