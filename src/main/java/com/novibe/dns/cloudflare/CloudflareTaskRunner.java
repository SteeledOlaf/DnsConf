package com.novibe.dns.cloudflare;

import com.novibe.common.DnsTaskRunner;
import com.novibe.common.base_structures.BypassRoute;
import com.novibe.common.data_sources.HostsOverrideListsLoader;
import com.novibe.common.exception.UserInputException;
import com.novibe.common.util.DonorDnsUtils;
import com.novibe.common.util.EnvParser;
import com.novibe.common.util.Log;
import com.novibe.dns.cloudflare.http.dto.response.list.GatewayListDto;
import com.novibe.dns.cloudflare.http.dto.response.rule.GatewayRuleDto;
import com.novibe.dns.cloudflare.service.CloudflareListPlanner;
import com.novibe.dns.cloudflare.service.ListService;
import com.novibe.dns.cloudflare.service.RulePrecedenceCounter;
import com.novibe.dns.cloudflare.service.RuleService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class CloudflareTaskRunner extends DnsTaskRunner<CloudflarePlan> {

    private static final int CLOUDFLARE_DNS_POLICY_LIMIT = 500;
    private static final String GOOGLE_AI_SECTION = "Google AI";

    private final ListService listService;
    private final RuleService ruleService;
    private final CloudflareListPlanner listPlanner;

    @Override
    public String providerName() {
        return "Cloudflare";
    }

    @Override
    protected void greetingMessage() {
        Log.global("Planning Profile " + dnsProfile.number() + " (CLOUDFLARE)");
        Log.common("New lists and disabled rules are created and verified before the previous generation is removed.");
    }

    @Override
    protected CloudflarePlan plan() {
        List<String> blockSources = EnvParser.parse(settings.block());
        List<String> redirectSources = EnvParser.parse(settings.redirect());

        if ((blockSources.isEmpty() || redirectSources.isEmpty()) && !settings.allowClear()) {
            throw UserInputException.noStackTrace(
                    "Cloudflare replaces both BLOCK and REDIRECT settings. Set ALLOW_CLEAR=true to explicitly clear an omitted setting."
            );
        }

        List<String> rawBlocks = blockListsLoader.fetchWebsites(blockSources);
        HostsOverrideListsLoader.PrioritizedOverrides loadedRedirects =
                overrideListsLoader.fetchWebsitesWithPrioritySection(redirectSources, GOOGLE_AI_SECTION);
        List<BypassRoute> rawRedirects = new ArrayList<>(loadedRedirects.routes());

        if (dnsProfile.donorDns() != null && !rawRedirects.isEmpty()) {
            Log.step("Replace domain IPs via the configured donor DNS");
            DonorDnsUtils.replaceIPs(rawRedirects, dnsProfile);
        }

        List<String> blocks = listPlanner.normalizeBlocks(rawBlocks);
        Set<String> priorityDomains = new HashSet<>(loadedRedirects.priorityDomains());
        List<BypassRoute> priorityRedirects = listPlanner.normalizePriorityRedirects(
                rawRedirects.stream()
                        .filter(route -> priorityDomains.contains(route.website()))
                        .toList()
        );
        List<BypassRoute> redirects = listPlanner.includePriorityRedirects(
                listPlanner.normalizeRedirects(rawRedirects), priorityRedirects
        );

        if (!blockSources.isEmpty() && blocks.isEmpty()) {
            throw UserInputException.noStackTrace(
                    "BLOCK sources produced no valid Cloudflare domains after normalization; existing configuration was preserved."
            );
        }
        if (!redirectSources.isEmpty() && redirects.isEmpty()) {
            throw UserInputException.noStackTrace(
                    "REDIRECT sources produced no valid Cloudflare routes after normalization; existing configuration was preserved."
            );
        }
        if (!redirectSources.isEmpty() && priorityRedirects.isEmpty()) {
            Log.fail("No # Google AI section was found in REDIRECT sources; no priority redirects were planned.");
        } else if (!priorityRedirects.isEmpty()) {
            Log.common("Google AI priority layer: %s domains".formatted(priorityRedirects.size()));
        }

        return new CloudflarePlan(
                blocks,
                redirects,
                priorityRedirects,
                blockSources.isEmpty() || redirectSources.isEmpty()
        );
    }

    @Override
    protected void apply(CloudflarePlan plan) {
        List<GatewayRuleDto> allExistingRules = ruleService.obtainExistingRules();
        List<GatewayRuleDto> oldRules = ruleService.managedRules(allExistingRules);
        List<GatewayListDto> oldLists = listService.obtainManagedLists();
        RulePrecedenceCounter precedence = RulePrecedenceCounter.providePrecedenceCounter(allExistingRules);

        Map<String, List<String>> redirectsByIp = listPlanner.redirectDomainsByIp(plan.redirects());
        int desiredRuleCount = (plan.blocks().isEmpty() ? 0 : 1)
                + ruleService.requiredOverrideRuleCount(redirectsByIp);
        boolean fullGenerationFits = allExistingRules.size() + desiredRuleCount <= CLOUDFLARE_DNS_POLICY_LIMIT;
        refreshGoogleAiFirst(plan, oldRules, fullGenerationFits);

        if (!fullGenerationFits) {
            Log.common("Full Cloudflare generation needs %s temporary DNS policy slots, but only %s are available."
                    .formatted(desiredRuleCount,
                            Math.max(0, CLOUDFLARE_DNS_POLICY_LIMIT - allExistingRules.size())));
            Log.common("Google AI was refreshed first; other managed rules and lists were preserved from the previous generation.");
            return;
        }

        List<GatewayListDto> newLists = new ArrayList<>();
        List<RuleService.CreatedRule> newRules = new ArrayList<>();

        try {
            if (!plan.blocks().isEmpty()) {
                List<GatewayListDto> blockLists = listService.createNewBlockLists(plan.blocks());
                newLists.addAll(blockLists);
                newRules.add(ruleService.createBlockingRule(blockLists, precedence.next()));
            }

            if (!redirectsByIp.isEmpty()) {
                for (Map.Entry<String, List<String>> entry : redirectsByIp.entrySet()) {
                    newRules.addAll(ruleService.createOverrideRules(entry.getValue(), entry.getKey(), precedence));
                }
            }

            ruleService.activateRules(newRules);
        } catch (RuntimeException creationFailure) {
            rollbackNewGeneration(newRules, newLists, creationFailure);
            throw creationFailure;
        }

        Log.step("New Cloudflare generation is active; removing previous managed rules");
        ruleService.removeRules(oldRules);
        Log.step("Removing previous managed lists");
        listService.removeLists(oldLists);
        Log.common("Cloudflare summary: created %s lists and %s rules; removed %s old lists and %s old rules"
                .formatted(newLists.size(), newRules.size(), oldLists.size(), oldRules.size()));
    }

    private void refreshGoogleAiFirst(CloudflarePlan plan,
                                      List<GatewayRuleDto> oldRules,
                                      boolean fullGenerationFits) {
        if (plan.priorityRedirects().isEmpty()) return;

        Map<String, List<String>> priorityByIp = listPlanner.redirectDomainsByIp(plan.priorityRedirects());
        int requiredSlots = ruleService.requiredOverrideRuleCount(priorityByIp);
        if (oldRules.size() < requiredSlots && fullGenerationFits) {
            Log.common("Google AI will be installed as part of the first full generation; no reusable managed slots exist yet.");
            return;
        }
        RuleService.PriorityUpdateResult result = ruleService.refreshPriorityOverrides(priorityByIp, oldRules);
        Log.common("Google AI priority refresh: %s desired rules applied across %s existing slots."
                .formatted(result.desiredRules(), result.refreshedSlots()));
        if (result.borrowedSlots() > 0) {
            Log.common("Google AI priority used %s slots previously assigned to lower-priority managed rules."
                    .formatted(result.borrowedSlots()));
        }
    }

    private void rollbackNewGeneration(List<RuleService.CreatedRule> rules,
                                       List<GatewayListDto> lists,
                                       RuntimeException originalFailure) {
        Log.fail("Cloudflare replacement failed; rolling back the new generation and preserving the previous one.");
        try {
            ruleService.removeCreatedRules(rules);
        } catch (RuntimeException rollbackFailure) {
            originalFailure.addSuppressed(rollbackFailure);
        }
        try {
            listService.removeLists(lists);
        } catch (RuntimeException rollbackFailure) {
            originalFailure.addSuppressed(rollbackFailure);
        }
    }

    @Override
    protected void finishMessage() {
        Log.global("Profile " + dnsProfile.number() + " (Cloudflare) set up successfully");
    }
}
