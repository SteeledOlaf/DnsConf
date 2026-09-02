package com.novibe.dns.cloudflare;

import com.novibe.common.DnsTaskRunner;
import com.novibe.common.base_structures.BypassRoute;
import com.novibe.common.exception.UserInputException;
import com.novibe.common.util.DonorDnsUtils;
import com.novibe.common.util.EnvParser;
import com.novibe.common.util.Log;
import com.novibe.dns.cloudflare.http.dto.response.list.GatewayListDto;
import com.novibe.dns.cloudflare.http.dto.response.rule.GatewayRuleDto;
import com.novibe.dns.cloudflare.service.ListService;
import com.novibe.dns.cloudflare.service.RulePrecedenceCounter;
import com.novibe.dns.cloudflare.service.RuleService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

import static com.novibe.common.config.EnvironmentVariables.BLOCK;
import static com.novibe.common.config.EnvironmentVariables.ALLOW_CLEAR;
import static com.novibe.common.config.EnvironmentVariables.REDIRECT;
import static java.util.Objects.nonNull;


@Service
@RequiredArgsConstructor
public class CloudflareTaskRunner extends DnsTaskRunner {

    private final ListService listService;
    private final RuleService ruleService;


    @Override
    protected void greetingMessage() {

        Log.global("Setting up Profile " + dnsProfile.number() + " (CLOUDFLARE)");
        Log.common("""
                Script behaviour: previously generated data is always about to be removed.
                - if you want to clear Cloudflare BLOCK/REDIRECT settings, launch this script without providing sources to related environment variables.
                - each line is mapped to an IP–domain pair; lines that cannot be parsed are skipped.
                """);
    }

    @Override
    public void process() {


        List<String> blockSources = EnvParser.parse(BLOCK);
        List<String> redirectSources = EnvParser.parse(REDIRECT);

        if ((blockSources.isEmpty() || redirectSources.isEmpty()) && !ALLOW_CLEAR) {
            throw UserInputException.noStackTrace(
                    "Cloudflare replaces both BLOCK and REDIRECT settings. Set ALLOW_CLEAR=true to explicitly clear an omitted setting."
            );
        }

        List<String> blocks = blockListsLoader.fetchWebsites(blockSources);
        List<BypassRoute> overrides = overrideListsLoader.fetchWebsites(redirectSources);

        if (!blockSources.isEmpty() && blocks.isEmpty()) {
            throw UserInputException.noStackTrace("BLOCK sources returned no valid domains; existing Cloudflare configuration was preserved.");
        }
        if (!redirectSources.isEmpty() && overrides.isEmpty()) {
            throw UserInputException.noStackTrace("REDIRECT sources returned no valid routes; existing Cloudflare configuration was preserved.");
        }

        Log.step("Remove old rules.");
        List<GatewayRuleDto> gatewayRuleDtos = ruleService.obtainExistingRules();
        List<GatewayRuleDto> remainingRules = ruleService.removeOldRules(gatewayRuleDtos);
        RulePrecedenceCounter precedenceCounter = RulePrecedenceCounter.providePrecedenceCounter(remainingRules);

        Log.step("Remove old lists.");
        listService.removeOldLists();

        Log.step("Creating new block lists");
        if (!blocks.isEmpty()) {
            List<GatewayListDto> gatewayListDtos = listService.createNewBlockLists(blocks);

            Log.step("Creating new blocking rule");
            ruleService.createNewBlockingRule(gatewayListDtos, precedenceCounter);
        } else {
            Log.fail("Websites to block were not provided");
        }

        Log.step("Creating new override lists");
        if (!overrides.isEmpty()) {
            listService.omitExcludedOverrides(overrides);

            if (nonNull(dnsProfile.donorDns())) {
                Log.step("Replace domain IPs via the configured donor DNS");
                DonorDnsUtils.replaceIPs(overrides, dnsProfile);
            }

            Map<String, List<GatewayListDto>> newOverrideLists = listService.createNewOverrideLists(overrides);

            Log.step("Creating new override rules");
            ruleService.createNewOverrideRules(newOverrideLists, precedenceCounter);
        } else {
            Log.fail("Websites to override were not provided");
        }
    }

    @Override
    protected void finishMessage() {
        Log.global("Profile " + dnsProfile.number() + " (Cloudflare) set up successfully");

    }
}
