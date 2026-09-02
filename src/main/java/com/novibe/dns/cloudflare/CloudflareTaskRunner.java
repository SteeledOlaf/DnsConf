package com.novibe.dns.cloudflare;

import com.novibe.common.DnsTaskRunner;
import com.novibe.common.base_structures.BypassRoute;
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
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class CloudflareTaskRunner extends DnsTaskRunner<CloudflarePlan> {

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
        List<BypassRoute> rawRedirects = overrideListsLoader.fetchWebsites(redirectSources);

        if (dnsProfile.donorDns() != null && !rawRedirects.isEmpty()) {
            Log.step("Replace domain IPs via the configured donor DNS");
            DonorDnsUtils.replaceIPs(rawRedirects, dnsProfile);
        }

        List<String> blocks = listPlanner.normalizeBlocks(rawBlocks);
        List<BypassRoute> redirects = listPlanner.normalizeRedirects(rawRedirects);

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

        return new CloudflarePlan(
                blocks,
                redirects,
                blockSources.isEmpty() || redirectSources.isEmpty()
        );
    }

    @Override
    protected void apply(CloudflarePlan plan) {
        List<GatewayRuleDto> allExistingRules = ruleService.obtainExistingRules();
        List<GatewayRuleDto> oldRules = ruleService.managedRules(allExistingRules);
        List<GatewayListDto> oldLists = listService.obtainManagedLists();
        RulePrecedenceCounter precedence = RulePrecedenceCounter.providePrecedenceCounter(allExistingRules);

        List<GatewayListDto> newLists = new ArrayList<>();
        List<RuleService.CreatedRule> newRules = new ArrayList<>();

        try {
            if (!plan.blocks().isEmpty()) {
                List<GatewayListDto> blockLists = listService.createNewBlockLists(plan.blocks());
                newLists.addAll(blockLists);
                newRules.add(ruleService.createBlockingRule(blockLists, precedence.next()));
            }

            if (!plan.redirects().isEmpty()) {
                Map<String, List<String>> redirectsByIp = listPlanner.redirectDomainsByIp(plan.redirects());
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
