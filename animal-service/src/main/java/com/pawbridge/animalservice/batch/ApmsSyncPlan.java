package com.pawbridge.animalservice.batch;

import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** A fixed KST collection window shared by every step in one execution. */
public record ApmsSyncPlan(LocalDate recentStart, LocalDate historyStart,
                           LocalDate updatedStart, LocalDate end, Map<LocalDate, LocalDate> monthlyStarts) {
    public static final String CONTRACT = "query-v2";
    public static final String LEGACY_CONTRACT = "updated-v1";
    private static final String MONTH_PREFIX = "apms.sync.month.";

    public ApmsSyncPlan(LocalDate recentStart, LocalDate historyStart, LocalDate updatedStart, LocalDate end) {
        this(recentStart, historyStart, updatedStart, end, Map.of());
    }
    public static final String CONTRACT_KEY = "apms.sync.contract";
    public static final String END_KEY = "apms.sync.end";

    public ApmsSyncPlan {
        monthlyStarts = Map.copyOf(monthlyStarts);
        if (recentStart == null || historyStart == null || updatedStart == null || end == null
                || recentStart.isAfter(end) || historyStart.isAfter(recentStart)
                || updatedStart.isAfter(end)) {
            throw new IllegalArgumentException("Invalid APMS collection window");
        }
        for (var entry : monthlyStarts.entrySet()) {
            if (entry.getKey().getDayOfMonth() != 1 || entry.getKey().isBefore(historyStart.withDayOfMonth(1))
                    || entry.getKey().isAfter(end) || entry.getValue().isBefore(updatedStart)
                    || entry.getValue().isAfter(end)) {
                throw new IllegalArgumentException("Invalid APMS monthly window");
            }
        }
    }

    public JobParameters parameters() {
        var builder = new JobParametersBuilder()
                .addString(CONTRACT_KEY, CONTRACT)
                .addString("apms.sync.recentStart", recentStart.toString())
                .addString("apms.sync.historyStart", historyStart.toString())
                .addString("apms.sync.updatedStart", updatedStart.toString())
                .addString(END_KEY, end.toString());
        // Separate parameters avoid the Batch metadata column's per-value length limit.
        new TreeMap<>(monthlyStarts).forEach((month, start) -> builder.addString(MONTH_PREFIX + month, start.toString()));
        return builder.toJobParameters();
    }

    public static ApmsSyncPlan from(JobParameters parameters) {
        if (!CONTRACT.equals(parameters.getString(CONTRACT_KEY))
                && !LEGACY_CONTRACT.equals(parameters.getString(CONTRACT_KEY))) {
            throw new IllegalArgumentException("Missing APMS collection contract");
        }
        Map<LocalDate, LocalDate> months = new TreeMap<>();
        if (CONTRACT.equals(parameters.getString(CONTRACT_KEY))) {
            for (String key : parameters.getParameters().keySet()) {
                if (key.startsWith(MONTH_PREFIX)) {
                    months.put(LocalDate.parse(key.substring(MONTH_PREFIX.length())), LocalDate.parse(parameters.getString(key)));
                }
            }
        }

        return new ApmsSyncPlan(LocalDate.parse(parameters.getString("apms.sync.recentStart")),
                LocalDate.parse(parameters.getString("apms.sync.historyStart")),
                LocalDate.parse(parameters.getString("apms.sync.updatedStart")),
                LocalDate.parse(parameters.getString(END_KEY)), months);
    }

    public List<Query> queries() {
        List<Query> result = new ArrayList<>();
        // Keep the original intake query, including new animals without updTm.
        result.add(new Query(recentStart, end, null, null));
        LocalDate month = historyStart.withDayOfMonth(1);
        while (!month.isAfter(end)) {
            LocalDate monthEnd = month.plusMonths(1).minusDays(1);
            result.add(new Query(month, monthEnd.isAfter(end) ? end : monthEnd, monthlyStarts.getOrDefault(month, updatedStart), end));
            month = month.plusMonths(1);
        }
        return List.copyOf(result);
    }

    public record Query(LocalDate intakeStart, LocalDate intakeEnd,
                        LocalDate updatedStart, LocalDate updatedEnd) { }
}
