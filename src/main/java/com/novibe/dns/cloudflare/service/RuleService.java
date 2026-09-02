package com.novibe.dns.cloudflare.service;

import com.novibe.common.util.Log;
import com.novibe.dns.cloudflare.http.CloudflareRuleClient;
import com.novibe.dns.cloudflare.http.dto.request.CreateRuleRequest;
import com.novibe.dns.cloudflare.http.dto.response.list.GatewayListDto;
import com.novibe.dns.cloudflare.http.dto.response.rule.GatewayRuleDto;
import com.novibe.dns.cloudflare.http.dto.response.rule.SingleRuleApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class RuleService {

    private static final String RULES_LIST_NAME_PREFIX = "Rules set by script";
    private static final int MAX_INLINE_TRAFFIC_LENGTH = 120_000;
    private static final int MAX_INLINE_VALUES = 100;

    private final CloudflareRuleClient cloudflareRuleClient;
    private final OwnershipMarker ownershipMarker;

    public CreatedRule createBlockingRule(List<GatewayListDto> lists, int precedence) {
        CreateRuleRequest request = CreateRuleRequest.builder()
                .name(RULES_LIST_NAME_PREFIX + " " + ownershipMarker.generation() + ": block")
                .precedence(precedence)
                .action("block")
                .description(ownershipMarker.description())
                .filters(List.of("dns"))
                .enabled(false)
                .traffic(makeTrafficExpression(lists))
                .build();
        return createRule(request);
    }

    public List<CreatedRule> createOverrideRules(List<String> domains,
                                                 String overrideIp,
                                                 RulePrecedenceCounter precedence) {
        List<String> expressions = makeInlineTrafficExpressions(domains);
        List<CreatedRule> result = new ArrayList<>();
        try {
            for (int index = 0; index < expressions.size(); index++) {
                String suffix = expressions.size() == 1 ? "" : " part " + (index + 1) + "/" + expressions.size();
                result.add(createOverrideRule(expressions.get(index), overrideIp, precedence.next(), suffix));
            }
            return List.copyOf(result);
        } catch (RuntimeException creationFailure) {
            try {
                removeCreatedRules(result);
            } catch (RuntimeException rollbackFailure) {
                creationFailure.addSuppressed(rollbackFailure);
            }
            throw creationFailure;
        }
    }

    private CreatedRule createOverrideRule(String traffic, String overrideIp, int precedence, String nameSuffix) {
        CreateRuleRequest request = CreateRuleRequest.builder()
                .name(RULES_LIST_NAME_PREFIX + " " + ownershipMarker.generation()
                        + " override to IP -> " + overrideIp + nameSuffix)
                .precedence(precedence)
                .action("override")
                .description(ownershipMarker.description())
                .filters(List.of("dns"))
                .enabled(false)
                .traffic(traffic)
                .ruleSettings(new CreateRuleRequest.RuleSettings(List.of(overrideIp)))
                .build();
        return createRule(request);
    }

    public void activateRules(List<CreatedRule> rules) {
        for (CreatedRule rule : rules) {
            CreateRuleRequest enabled = copyWithEnabled(rule.request(), true);
            SingleRuleApiResponse response = cloudflareRuleClient.updateRule(rule.ruleId(), enabled);
            requireSuccess(response, "activate rule " + rule.ruleId());
        }
    }

    public List<GatewayRuleDto> obtainExistingRules() {
        return cloudflareRuleClient.getRules();
    }

    public List<GatewayRuleDto> managedRules(Collection<GatewayRuleDto> rules) {
        return rules.stream()
                .filter(rule -> rule.getName() != null && rule.getName().startsWith(RULES_LIST_NAME_PREFIX))
                .filter(rule -> ownershipMarker.owns(rule.getDescription())
                        || ownershipMarker.isLegacySession(rule.getDescription()))
                .toList();
    }

    public void removeRules(Collection<GatewayRuleDto> rules) {
        List<String> errors = new ArrayList<>();
        int removed = 0;
        for (GatewayRuleDto rule : rules) {
            try {
                SingleRuleApiResponse response = cloudflareRuleClient.removeRuleById(rule.getId());
                if (response == null || !response.isSuccess()) {
                    errors.add(rule.getId() + ": " + (response == null ? "empty response" : response.getErrors()));
                } else {
                    Log.progress(++removed + "/" + rules.size() + " removed");
                }
            } catch (RuntimeException exception) {
                errors.add(rule.getId() + ": " + exception.getMessage());
            }
        }
        if (!errors.isEmpty()) {
            throw new IllegalStateException("Failed to remove Cloudflare Gateway rules: " + errors);
        }
    }

    public void removeCreatedRules(Collection<CreatedRule> rules) {
        List<String> errors = new ArrayList<>();
        for (CreatedRule rule : rules) {
            try {
                SingleRuleApiResponse response = cloudflareRuleClient.removeRuleById(rule.ruleId());
                if (response == null || !response.isSuccess()) {
                    errors.add(rule.ruleId() + ": " + (response == null ? "empty response" : response.getErrors()));
                }
            } catch (RuntimeException exception) {
                errors.add(rule.ruleId() + ": " + exception.getMessage());
            }
        }
        if (!errors.isEmpty()) throw new IllegalStateException("Failed to roll back Cloudflare rules: " + errors);
    }

    private CreatedRule createRule(CreateRuleRequest request) {
        Log.io("Posting new disabled rule: " + request.name());
        SingleRuleApiResponse response = cloudflareRuleClient.createRule(request);
        requireSuccess(response, "create rule " + request.name());
        if (response.getResult() == null) {
            throw new IllegalStateException("Cloudflare returned no rule after creating " + request.name());
        }
        return new CreatedRule(response.getResult().getId(), request);
    }

    private static void requireSuccess(SingleRuleApiResponse response, String operation) {
        if (response == null || !response.isSuccess()) {
            throw new IllegalStateException("Failed to " + operation + ": "
                    + (response == null ? "empty response" : response.getErrors()));
        }
    }

    private static CreateRuleRequest copyWithEnabled(CreateRuleRequest request, boolean enabled) {
        return new CreateRuleRequest(
                request.name(), request.description(), request.action(), request.filters(), request.traffic(),
                request.precedence(), request.ruleSettings(), enabled
        );
    }

    private static String makeTrafficExpression(List<GatewayListDto> lists) {
        return lists.stream()
                .map(GatewayListDto::getId)
                .map(UUID::toString)
                .map("any(dns.domains[*] in $%s)"::formatted)
                .collect(Collectors.joining(" or "));
    }

    static List<String> makeInlineTrafficExpressions(List<String> domains) {
        if (domains.isEmpty()) {
            throw new IllegalArgumentException("An override rule requires at least one domain");
        }

        List<String> expressions = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int currentValues = 0;
        for (String domain : domains) {
            String clause = "any(dns.domains[*] == \"" + escapeExpressionString(domain) + "\")";
            int separatorLength = current.isEmpty() ? 0 : 4;
            if (!current.isEmpty() && (currentValues >= MAX_INLINE_VALUES
                    || current.length() + separatorLength + clause.length() > MAX_INLINE_TRAFFIC_LENGTH)) {
                expressions.add(current.toString());
                current.setLength(0);
                currentValues = 0;
            }
            if (!current.isEmpty()) current.append(" or ");
            current.append(clause);
            currentValues++;
        }
        expressions.add(current.toString());
        return List.copyOf(expressions);
    }

    private static String escapeExpressionString(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    public record CreatedRule(String ruleId, CreateRuleRequest request) {
    }
}
