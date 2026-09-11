package com.pawbridge.animalservice.reconciliation;

import java.nio.file.*;
import java.time.YearMonth;
import java.util.Map;

/** Separate operator execution: never creates or updates Spring Batch job metadata. */
public final class HistoryReconciliation {
    public static HistoryPlan approvedPlan(Path file, String hash, YearMonth month, int maxChanges,
                                           boolean allowIncomplete) throws Exception {
        if (Files.size(file) > 64L * 1024 * 1024) HistoryPlan.fail("Plan exceeds size limit");
        byte[] bytes = Files.readAllBytes(file);
        if (!HistoryJournal.hash(bytes).equals(hash)) HistoryPlan.fail("Reviewed plan hash mismatch");
        var plan = HistoryPlan.JSON.readValue(bytes, HistoryPlan.class);
        plan.validate();
        if (!plan.month().equals(month.toString()) || maxChanges < 1 || plan.entries().size() > maxChanges)
            HistoryPlan.fail("Approved target scope exceeded");
        if (!allowIncomplete && !plan.warnings().isEmpty()) HistoryPlan.fail("Incomplete source requires explicit acknowledgement");
        return plan;
    }
    public static void apply(HistoryPlan plan, String hash, HistoryStore store, HistorySearch search,
                             HistoryJournal journal) throws Exception {
        plan.validate();
        if (!plan.database().equals(store.identity()) || !plan.searchIndex().equals(search.identity()))
            HistoryPlan.fail("Plan target environment mismatch");
        store.acquire();
        int completed = 0;
        for (var entry : plan.entries()) {
            String number = entry.source().getDesertionNo();
            journal.event("DB_INTENT", Map.of("planHash", hash, "apmsNo", number));
            try {
                var result = store.apply(entry);
                journal.event("DB_COMMITTED", Map.of("apmsNo", number, "id", result.id(), "action", result.action()));
                store.sync(result.id(), number, search, plan.searchIndex());
                journal.event("SEARCH_SYNCED", Map.of("apmsNo", number, "id", result.id()));
                completed++;
            } catch (Exception failure) {
                journal.event("INCOMPLETE", Map.of("apmsNo", number, "completed", completed));
                throw failure;
            }
        }
        search.refresh(plan.searchIndex());
        journal.event("COMPLETED", Map.of("planHash", hash, "completed", completed,
                "sourceComplete", plan.warnings().isEmpty()));
    }
}
