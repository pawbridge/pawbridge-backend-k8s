package com.pawbridge.animalservice.reconciliation;

import com.pawbridge.animalservice.dto.apms.ApmsAnimal;
import java.nio.file.*;
import java.nio.channels.FileChannel;
import java.nio.ByteBuffer;
import java.sql.DriverManager;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;

/** Explicit standalone main. Does not start Spring, HTTP listeners, schedulers or schema initialization. */
public final class HistoryReconciliationCli {
    public static void main(String[] args) {
        try { run(args, System.getenv()); }
        catch (Exception failure) {
            // JDBC/provider exceptions can embed credentials, URLs or animal details. Inspect the private journal.
            if (failure instanceof HistoryPlan.RejectedPlanException) System.err.println(failure.getMessage());
            System.err.println("History reconciliation stopped (" + failure.getClass().getSimpleName()
                    + "). No success claimed. Inspect the plan/journal and target state before resuming.");
            System.exit(1);
        }
    }
    static void run(String[] args, Map<String, String> environment) throws Exception {
        var options = options(args);
        String mode = options.getOrDefault("mode", "plan");
        YearMonth month = YearMonth.parse(required(options, "month"));
        var today = LocalDate.now(ZoneId.of("Asia/Seoul"));
        if (month.isAfter(YearMonth.from(today))) HistoryPlan.fail("Cannot reconcile a future month");
        Path path = Path.of(required(options, "plan")).toAbsolutePath();
        HistoryPlan approved = null;
        if (mode.equals("apply")) {
            approved = HistoryReconciliation.approvedPlan(path, required(options, "sha256"), month,
                    Integer.parseInt(required(options, "max-changes")), Boolean.parseBoolean(options.getOrDefault("allow-incomplete-source", "false")));
            Path journal = Path.of(required(options, "journal")).toAbsolutePath();
            if (journal.normalize().equals(path.normalize()) || (Files.exists(journal) && Files.isSameFile(journal, path)))
                HistoryPlan.fail("Plan and journal must be different files");
        } else if (!mode.equals("plan")) HistoryPlan.fail("Mode must be plan or apply");
        else if (Files.exists(path)) HistoryPlan.fail("Plan already exists; use a new path");
        String jdbc = required(environment, "HISTORY_JDBC_URL");
        if (!jdbc.startsWith("jdbc:mysql://") || jdbc.toLowerCase(Locale.ROOT).contains("autoreconnect"))
            HistoryPlan.fail("MySQL required; automatic reconnect is forbidden for session locks");
        var properties = new Properties();
        properties.setProperty("user", required(environment, "HISTORY_DB_USER"));
        properties.setProperty("password", required(environment, "HISTORY_DB_PASSWORD"));
        properties.setProperty("autoReconnect", "false");
        properties.setProperty("connectTimeout", "10000");
        properties.setProperty("socketTimeout", "60000");
        var search = new HistorySearch(required(environment, "HISTORY_ES_URL"), environment.get("HISTORY_ES_AUTHORIZATION"), environment.get("HISTORY_ES_CA_CERT"));
        try (var store = new HistoryStore(DriverManager.getConnection(jdbc, properties))) {
            if (approved != null) {
                try (var journal = new HistoryJournal(Path.of(required(options, "journal")), required(options, "sha256"))) {
                    HistoryReconciliation.apply(approved, required(options, "sha256"), store, search, journal);
                }
                System.out.println("Apply completed: " + approved.entries().size() + " targets; sourceComplete=" + approved.warnings().isEmpty());
            } else {
                String base = required(environment, "APMS_API_BASE_URL").replaceAll("/+$", "");
                String key = required(environment, "APMS_API_SERVICE_KEY");
                var http = new HistoryHttp();
                var collector = new HistoryCollector((period, page) -> {
                    String begin = period.atDay(1).format(DateTimeFormatter.BASIC_ISO_DATE);
                    LocalDate last = period.atEndOfMonth().isAfter(today) ? today : period.atEndOfMonth();
                    String end = last.format(DateTimeFormatter.BASIC_ISO_DATE);
                    String url = base + "/abandonmentPublic_v2?serviceKey=" + URLEncoder.encode(key, StandardCharsets.UTF_8)
                            + "&pageNo=" + page + "&numOfRows=1000&bgnde=" + begin + "&endde=" + end + "&_type=json";
                    var response = http.request("GET", url, null, null, false).body().path("response");
                    if (!"00".equals(response.path("header").path("resultCode").asText())) HistoryPlan.fail("APMS returned an error");
                    var body = response.path("body");
                    var items = new ArrayList<ApmsAnimal>();
                    var node = body.path("items").path("item");
                    if (!node.isMissingNode() && !node.isNull()) {
                        if (!node.isArray()) HistoryPlan.fail("Unexpected APMS items shape");
                        for (var item : node) items.add(HistoryPlan.JSON.readerFor(ApmsAnimal.class)
                                .without(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).readValue(item));
                    }
                    return new HistoryCollector.Page(body.path("pageNo").asInt(-1), body.path("totalCount").asInt(-1), items);
                });
                var plan = store.plan(month, collector.collect(month), search.identity());
                byte[] bytes = HistoryPlan.JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(plan);
                try (var channel = FileChannel.open(path, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                    var buffer = ByteBuffer.wrap(bytes);
                    while (buffer.hasRemaining()) channel.write(buffer);
                    channel.force(true);
                }
                System.out.println("Plan created: targets=" + plan.entries().size() + ", reported=" + plan.reported()
                        + ", observed=" + plan.observed() + ", warnings=" + plan.warnings().size());
                System.out.println("SHA-256: " + HistoryJournal.hash(bytes));
            }
        }
    }
    static Map<String, String> options(String[] args) {
        var result = new HashMap<String, String>();
        var allowed = Set.of("mode", "month", "plan", "sha256", "max-changes", "journal", "allow-incomplete-source");
        if (args.length % 2 != 0) HistoryPlan.fail("Options require explicit values");
        for (int i = 0; i < args.length; i += 2) {
            if (!args[i].startsWith("--") || !allowed.contains(args[i].substring(2))
                    || result.putIfAbsent(args[i].substring(2), args[i + 1]) != null) HistoryPlan.fail("Unknown or duplicate option");
        }
        if (result.containsKey("allow-incomplete-source") && !Set.of("true", "false").contains(result.get("allow-incomplete-source")))
            HistoryPlan.fail("Acknowledgement must be true or false");
        return result;
    }
    private static String required(Map<String, String> values, String key) {
        String value = values.get(key);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Missing required option or environment field: " + key);
        return value;
    }
}
