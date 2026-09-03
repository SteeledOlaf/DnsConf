package com.novibe.dns.cloudflare;

import com.novibe.common.base_structures.BypassRoute;
import com.novibe.common.reconciliation.ReconciliationPlan;

import java.util.List;

public record CloudflarePlan(List<String> blocks,
                             List<BypassRoute> redirects,
                             List<BypassRoute> priorityRedirects,
                             boolean clearsConfiguration) implements ReconciliationPlan {

    public CloudflarePlan {
        blocks = List.copyOf(blocks);
        redirects = List.copyOf(redirects);
        priorityRedirects = List.copyOf(priorityRedirects);
    }

    @Override
    public int blockEntries() {
        return blocks.size();
    }

    @Override
    public int redirectEntries() {
        return redirects.size();
    }
}
