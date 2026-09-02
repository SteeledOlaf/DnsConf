package com.novibe.common.reconciliation;

import java.util.List;

public interface ReconciliationPlan {

    int blockEntries();

    int redirectEntries();

    boolean clearsConfiguration();

    default List<String> summary() {
        return List.of(
                "Desired block entries: " + blockEntries(),
                "Desired redirect entries: " + redirectEntries(),
                "Explicit clear: " + clearsConfiguration()
        );
    }
}
