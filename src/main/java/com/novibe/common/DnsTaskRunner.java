package com.novibe.common;

import com.novibe.common.base_structures.DnsProfile;
import com.novibe.common.config.AppSettings;
import com.novibe.common.data_sources.HostsBlockListsLoader;
import com.novibe.common.data_sources.HostsOverrideListsLoader;
import com.novibe.common.reconciliation.ReconciliationPlan;
import com.novibe.common.util.Log;
import lombok.Setter;
import org.springframework.beans.factory.annotation.Autowired;

@Setter(onMethod_ = @Autowired)
public abstract class DnsTaskRunner<P extends ReconciliationPlan> implements DnsProvider {

    protected DnsProfile dnsProfile;
    protected HostsBlockListsLoader blockListsLoader;
    protected HostsOverrideListsLoader overrideListsLoader;
    protected AppSettings settings;

    protected abstract void greetingMessage();

    protected abstract P plan();

    protected abstract void apply(P plan);

    protected abstract void finishMessage();

    public final void run() {
        greetingMessage();
        P reconciliationPlan = plan();
        reconciliationPlan.summary().forEach(Log::common);
        if (settings.dryRun()) {
            Log.global("DRY_RUN: no DNS provider changes were applied");
            return;
        }
        apply(reconciliationPlan);
        finishMessage();
    }
}
