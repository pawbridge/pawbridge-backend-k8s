package com.pawbridge.animalservice.reconciliation;

import com.pawbridge.animalservice.dto.apms.ApmsAnimal;
import java.time.YearMonth;
import java.util.*;

/** Two bounded complete scans for suspect counts; never invents an absent record. */
public final class HistoryCollector {
    public interface Source { Page fetch(YearMonth month, int page) throws Exception; }
    public record Page(int page, int reported, List<ApmsAnimal> items) { }
    public record Snapshot(int reported, List<ApmsAnimal> items, List<String> warnings) { }
    private final Source source;
    public HistoryCollector(Source source) { this.source = source; }
    public Snapshot collect(YearMonth month) throws Exception {
        Snapshot first = scan(month);
        if (first.warnings().isEmpty()) return first;
        Snapshot second = scan(month);
        Map<String, ApmsAnimal> original = index(first.items());
        Map<String, ApmsAnimal> repeated = index(second.items());
        if (!original.equals(repeated)) HistoryPlan.fail("Source changed between scans; generate a new plan later");
        var warnings = new ArrayList<>(second.warnings());
        warnings.add("Repeated scan returned the same records; provider completeness remains unverified");
        return new Snapshot(second.reported(), second.items(), List.copyOf(warnings));
    }
    private Snapshot scan(YearMonth month) throws Exception {
        var items = new LinkedHashMap<String, ApmsAnimal>();
        var warnings = new LinkedHashSet<String>();
        int total = -1;
        for (int page = 1; page <= 20; page++) {
            Page response = source.fetch(month, page);
            if (response.page() != page || response.reported() < 0 || response.reported() > 20000
                    || response.items() == null || response.items().size() > 1000) HistoryPlan.fail("Invalid APMS page");
            if (total == -1) total = response.reported();
            if (total != response.reported()) HistoryPlan.fail("APMS total changed during scan");
            for (var animal : response.items()) {
                HistoryPlan.validateCollectedSource(animal, month);
                var previous = items.putIfAbsent(animal.getDesertionNo(), animal);
                if (previous != null) {
                    if (!previous.equals(animal)) HistoryPlan.fail("Conflicting duplicate APMS record");
                    warnings.add("Duplicate APMS IDs returned");
                }
            }
            if (page >= Math.max(1, (total + 999) / 1000)) {
                if (items.size() != total) warnings.add("Reported count differs from unique records");
                return new Snapshot(total, List.copyOf(items.values()), List.copyOf(warnings));
            }
        }
        throw new IllegalStateException("APMS request budget exceeded");
    }
    private static Map<String, ApmsAnimal> index(List<ApmsAnimal> items) {
        var result = new TreeMap<String, ApmsAnimal>();
        for (var item : items) result.put(item.getDesertionNo(), item);
        return result;
    }
}
