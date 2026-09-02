package com.novibe.dns.next_dns;

import com.novibe.common.base_structures.BypassRoute;
import com.novibe.common.reconciliation.ReconciliationPlan;

import java.util.List;

public record NextDnsPlan(List<String> blocks,
                          List<BypassRoute> redirects,
                          boolean blockSourceConfigured,
                          boolean redirectSourceConfigured,
                          boolean clearsConfiguration) implements ReconciliationPlan {

    public NextDnsPlan {
        blocks = List.copyOf(blocks);
        redirects = List.copyOf(redirects);
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
