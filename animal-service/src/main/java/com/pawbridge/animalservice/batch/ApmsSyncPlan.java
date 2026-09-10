package com.pawbridge.animalservice.batch;

import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/** A fixed KST collection window shared by every step in one execution. */
public record ApmsSyncPlan(LocalDate recentStart, LocalDate historyStart,
                           LocalDate updatedStart, LocalDate end) {
    public static final String CONTRACT = "updated-v1";
    public static final String CONTRACT_KEY = "apms.sync.contract";
    public static final String END_KEY = "apms.sync.end";

    public ApmsSyncPlan {
        if (recentStart == null || historyStart == null || updatedStart == null || end == null
                || recentStart.isAfter(end) || historyStart.isAfter(recentStart)
                || updatedStart.isAfter(end)) {
            throw new IllegalArgumentException("Invalid APMS collection window");
        }
    }

    public JobParameters parameters() {
        return new JobParametersBuilder()
                .addString(CONTRACT_KEY, CONTRACT)
                .addString("apms.sync.recentStart", recentStart.toString())
                .addString("apms.sync.historyStart", historyStart.toString())
                .addString("apms.sync.updatedStart", updatedStart.toString())
                .addString(END_KEY, end.toString()).toJobParameters();
    }

    public static ApmsSyncPlan from(JobParameters parameters) {
        if (!CONTRACT.equals(parameters.getString(CONTRACT_KEY))) {
            throw new IllegalArgumentException("Missing APMS collection contract");
        }
        return new ApmsSyncPlan(LocalDate.parse(parameters.getString("apms.sync.recentStart")),
                LocalDate.parse(parameters.getString("apms.sync.historyStart")),
                LocalDate.parse(parameters.getString("apms.sync.updatedStart")),
                LocalDate.parse(parameters.getString(END_KEY)));
    }

    public List<Query> queries() {
        List<Query> result = new ArrayList<>();
        // Keep the original intake query, including new animals without updTm.
        result.add(new Query(recentStart, end, null, null));
        LocalDate month = historyStart.withDayOfMonth(1);
        while (!month.isAfter(end)) {
            LocalDate monthEnd = month.plusMonths(1).minusDays(1);
            result.add(new Query(month, monthEnd.isAfter(end) ? end : monthEnd, updatedStart, end));
            month = month.plusMonths(1);
        }
        return List.copyOf(result);
    }

    public record Query(LocalDate intakeStart, LocalDate intakeEnd,
                        LocalDate updatedStart, LocalDate updatedEnd) { }
}
