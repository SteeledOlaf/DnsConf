package com.novibe.dns.next_dns;

import com.novibe.common.DnsTaskRunner;
import com.novibe.common.base_structures.BypassRoute;
import com.novibe.common.exception.UserInputException;
import com.novibe.common.util.DonorDnsUtils;
import com.novibe.common.util.EnvParser;
import com.novibe.common.util.Log;
import com.novibe.dns.next_dns.service.NextDnsDenyService;
import com.novibe.dns.next_dns.service.NextDnsRewriteService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class NextDnsTaskRunner extends DnsTaskRunner<NextDnsPlan> {

    private final NextDnsRewriteService nextDnsRewriteService;
    private final NextDnsDenyService nextDnsDenyService;

    @Override
    public String providerName() {
        return "NextDNS";
    }

    @Override
    public void greetingMessage() {
        Log.global("Planning Profile " + dnsProfile.number() + " (NextDNS)");
        Log.common("Existing entries are preserved unless a replacement or explicit ALLOW_CLEAR operation is planned.");
    }

    @Override
    protected NextDnsPlan plan() {
        List<String> blockSources = EnvParser.parse(settings.block());
        List<String> redirectSources = EnvParser.parse(settings.redirect());
        boolean clearAll = blockSources.isEmpty() && redirectSources.isEmpty();

        if (clearAll && !settings.allowClear()) {
            throw UserInputException.noStackTrace(
                    "BLOCK and REDIRECT are both empty. Set ALLOW_CLEAR=true to explicitly remove all NextDNS settings."
            );
        }

        List<String> blocks = blockListsLoader.fetchWebsites(blockSources);
        List<BypassRoute> redirects = overrideListsLoader.fetchWebsites(redirectSources);

        if (!blockSources.isEmpty() && blocks.isEmpty()) {
            throw UserInputException.noStackTrace("BLOCK sources returned no valid domains; no NextDNS changes were applied.");
        }
        if (!redirectSources.isEmpty() && redirects.isEmpty()) {
            throw UserInputException.noStackTrace("REDIRECT sources returned no valid routes; no NextDNS changes were applied.");
        }

        if (dnsProfile.donorDns() != null && !redirects.isEmpty()) {
            Log.step("Replace domain IPs via the configured donor DNS");
            DonorDnsUtils.replaceIPs(redirects, dnsProfile);
        }

        return new NextDnsPlan(
                blocks,
                redirects,
                !blockSources.isEmpty(),
                !redirectSources.isEmpty(),
                clearAll
        );
    }

    @Override
    protected void apply(NextDnsPlan plan) {
        if (plan.clearsConfiguration()) {
            Log.step("Explicitly removing all NextDNS deny and rewrite settings");
            nextDnsDenyService.removeAll();
            nextDnsRewriteService.removeAll();
            return;
        }

        if (plan.blockSourceConfigured()) {
            Log.step("Prepare and save NextDNS denylist");
            List<String> newDomains = nextDnsDenyService.omitExistingDenys(plan.blocks());
            nextDnsDenyService.saveDenyList(newDomains);
            Log.common("NextDNS deny summary: %s new entries, %s already present"
                    .formatted(newDomains.size(), plan.blocks().size() - newDomains.size()));
        }

        if (plan.redirectSourceConfigured()) {
            Log.step("Reconcile NextDNS rewrites");
            nextDnsRewriteService.reconcile(plan.redirects());
            Log.common("NextDNS rewrite reconciliation completed for " + plan.redirects().size() + " desired entries");
        }
    }

    @Override
    protected void finishMessage() {
        Log.global("Profile " + dnsProfile.number() + " (NextDNS) set up successfully");
    }
}
