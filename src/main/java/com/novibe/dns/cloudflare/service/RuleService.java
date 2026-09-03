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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
        List<OverrideRuleTemplate> templates = makeOverrideRuleTemplates(domains, overrideIp);
        List<CreatedRule> result = new ArrayList<>();
        try {
            for (OverrideRuleTemplate template : templates) {
                result.add(createRule(toRequest(template, precedence.next(), false)));
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

    public int requiredOverrideRuleCount(Map<String, List<String>> redirectsByIp) {
        return redirectsByIp.entrySet().stream()
                .mapToInt(entry -> makeInlineTrafficExpressions(entry.getValue()).size())
                .sum();
    }

    public PriorityUpdateResult refreshPriorityOverrides(Map<String, List<String>> redirectsByIp,
                                                         Collection<GatewayRuleDto> managedRules) {
        List<OverrideRuleTemplate> desired = redirectsByIp.entrySet().stream()
                .flatMap(entry -> makeOverrideRuleTemplates(entry.getValue(), entry.getKey()).stream())
                .toList();
        if (desired.isEmpty()) return new PriorityUpdateResult(0, 0, 0);

        Set<String> priorityDomains = redirectsByIp.values().stream()
                .flatMap(Collection::stream)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        List<GatewayRuleDto> currentPriority = managedRules.stream()
                .filter(rule -> containsAnyDomain(rule.getTraffic(), priorityDomains))
                .toList();

        LinkedHashSet<GatewayRuleDto> candidates = new LinkedHashSet<>(currentPriority);
        managedRules.stream()
                .filter(this::isOverrideRule)
                .forEach(candidates::add);
        candidates.addAll(managedRules);

        int slotsNeeded = Math.max(desired.size(), currentPriority.size());
        if (candidates.size() < slotsNeeded) {
            throw new IllegalStateException("Google AI priority update needs %s reusable managed rule slots, but only %s are available"
                    .formatted(slotsNeeded, candidates.size()));
        }

        List<GatewayRuleDto> slots = candidates.stream().limit(slotsNeeded).toList();
        List<UpdatedRule> updated = new ArrayList<>();
        try {
            for (int index = 0; index < slots.size(); index++) {
                GatewayRuleDto slot = slots.get(index);
                OverrideRuleTemplate template = desired.get(index % desired.size());
                CreateRuleRequest replacement = toRequest(template, slot.getPrecedence(), true);
                CreateRuleRequest backup = snapshot(slot);

                Log.io("Refreshing Google AI rule in existing slot: " + slot.getId());
                SingleRuleApiResponse response = cloudflareRuleClient.updateRule(slot.getId(), replacement);
                requireSuccess(response, "refresh Google AI rule " + slot.getId());
                updated.add(new UpdatedRule(slot.getId(), backup));
            }
        } catch (RuntimeException updateFailure) {
            rollbackUpdatedRules(updated, updateFailure);
            throw updateFailure;
        }

        int borrowedSlots = Math.max(0, desired.size() - currentPriority.size());
        return new PriorityUpdateResult(desired.size(), slots.size(), borrowedSlots);
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

    private List<OverrideRuleTemplate> makeOverrideRuleTemplates(List<String> domains, String overrideIp) {
        List<String> expressions = makeInlineTrafficExpressions(domains);
        List<OverrideRuleTemplate> templates = new ArrayList<>();
        for (int index = 0; index < expressions.size(); index++) {
            String suffix = expressions.size() == 1 ? "" : " part " + (index + 1) + "/" + expressions.size();
            templates.add(new OverrideRuleTemplate(expressions.get(index), overrideIp, suffix));
        }
        return List.copyOf(templates);
    }

    private CreateRuleRequest toRequest(OverrideRuleTemplate template, int precedence, boolean enabled) {
        return CreateRuleRequest.builder()
                .name(RULES_LIST_NAME_PREFIX + " " + ownershipMarker.generation()
                        + " override to IP -> " + template.overrideIp() + template.nameSuffix())
                .precedence(precedence)
                .action("override")
                .description(ownershipMarker.description())
                .filters(List.of("dns"))
                .enabled(enabled)
                .traffic(template.traffic())
                .ruleSettings(new CreateRuleRequest.RuleSettings(List.of(template.overrideIp())))
                .build();
    }

    private boolean isOverrideRule(GatewayRuleDto rule) {
        if ("override".equalsIgnoreCase(rule.getAction())) return true;
        GatewayRuleDto.GatewayRuleSettingsDto settings = rule.getRuleSettings();
        if (settings != null && settings.getOverrideIps() != null && !settings.getOverrideIps().isEmpty()) return true;
        return rule.getName() != null && rule.getName().toLowerCase().contains("override");
    }

    private static boolean containsAnyDomain(String traffic, Set<String> domains) {
        if (traffic == null || traffic.isBlank()) return false;
        return domains.stream().anyMatch(domain -> traffic.contains("\"" + escapeExpressionString(domain) + "\""));
    }

    private static CreateRuleRequest snapshot(GatewayRuleDto rule) {
        CreateRuleRequest.RuleSettings settings = null;
        if (rule.getRuleSettings() != null && rule.getRuleSettings().getOverrideIps() != null
                && !rule.getRuleSettings().getOverrideIps().isEmpty()) {
            settings = new CreateRuleRequest.RuleSettings(rule.getRuleSettings().getOverrideIps());
        }
        String action = rule.getAction();
        if (action == null || action.isBlank()) action = settings == null ? "block" : "override";
        return new CreateRuleRequest(
                rule.getName(), rule.getDescription(), action, List.of("dns"), rule.getTraffic(),
                rule.getPrecedence(), settings, rule.isEnabled()
        );
    }

    private void rollbackUpdatedRules(List<UpdatedRule> updated, RuntimeException originalFailure) {
        List<String> rollbackErrors = new ArrayList<>();
        for (int index = updated.size() - 1; index >= 0; index--) {
            UpdatedRule rule = updated.get(index);
            try {
                SingleRuleApiResponse response = cloudflareRuleClient.updateRule(rule.ruleId(), rule.backup());
                if (response == null || !response.isSuccess()) {
                    rollbackErrors.add(rule.ruleId() + ": "
                            + (response == null ? "empty response" : response.getErrors()));
                }
            } catch (RuntimeException rollbackFailure) {
                rollbackErrors.add(rule.ruleId() + ": " + rollbackFailure.getMessage());
            }
        }
        if (!rollbackErrors.isEmpty()) {
            originalFailure.addSuppressed(new IllegalStateException(
                    "Failed to roll back Google AI rule updates: " + rollbackErrors
            ));
        }
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

    public record PriorityUpdateResult(int desiredRules, int refreshedSlots, int borrowedSlots) {
    }

    private record UpdatedRule(String ruleId, CreateRuleRequest backup) {
    }

    private record OverrideRuleTemplate(String traffic, String overrideIp, String nameSuffix) {
    }
}
