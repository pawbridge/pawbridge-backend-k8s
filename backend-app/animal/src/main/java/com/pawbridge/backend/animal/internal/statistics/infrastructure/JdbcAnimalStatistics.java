package com.pawbridge.backend.animal.internal.statistics.infrastructure;

import com.pawbridge.backend.animal.internal.statistics.application.AnimalStatistics;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcOperations;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionTemplate;

/** Original JPQL aggregate predicates and service rules adapted to explicit PostgreSQL SELECTs. */
public final class JdbcAnimalStatistics implements AnimalStatistics {
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private final NamedParameterJdbcOperations jdbc;
    private final TransactionOperations reads;
    private final Clock clock;

    public JdbcAnimalStatistics(DataSource source, PlatformTransactionManager manager) {
        this(source, manager, Clock.system(SEOUL));
    }

    public JdbcAnimalStatistics(DataSource source, PlatformTransactionManager manager, Clock clock) {
        this.jdbc = new NamedParameterJdbcTemplate(new JdbcTemplate(source));
        TransactionTemplate transaction = new TransactionTemplate(manager);
        transaction.setReadOnly(true);
        this.reads = transaction;
        this.clock = clock.withZone(SEOUL);
    }

    JdbcAnimalStatistics(NamedParameterJdbcOperations jdbc, TransactionOperations reads, Clock clock) {
        this.jdbc = jdbc;
        this.reads = reads;
        this.clock = clock.withZone(SEOUL);
    }

    @Override
    public Today today() {
        return reads.execute(status -> {
            LocalDate today = LocalDate.now(clock);
            var parameters = new MapSqlParameterSource("today", today).addValue("status", Status.ADOPTED.name());
            Long rescued = jdbc.queryForObject("SELECT count(*) FROM pawbridge_animal.animals WHERE happen_date=:today", parameters, Long.class);
            Long adopted = jdbc.queryForObject("SELECT count(*) FROM pawbridge_animal.animals WHERE status=:status AND CAST(apms_updated_at AS date)=:today", parameters, Long.class);
            return new Today(today, rescued, adopted);
        });
    }

    @Override
    public List<StatusCount> status(LocalDate startDate, LocalDate endDate) {
        return reads.execute(ignored -> {
            LocalDate[] range = resolveDefaultDateRange(startDate, endDate);
            return jdbc.query("""
                    SELECT status, count(*) AS animal_count FROM pawbridge_animal.animals
                    WHERE happen_date BETWEEN :startDate AND :endDate
                    GROUP BY status ORDER BY count(*) DESC
                    """, parameters(range[0], range[1]), (row, index) -> new StatusCount(
                    Status.valueOf(row.getString("status")), row.getObject("animal_count", Long.class)));
        });
    }

    @Override
    public List<RegionalCount> regional(LocalDate startDate, LocalDate endDate) {
        return reads.execute(ignored -> {
            LocalDate[] range = resolveDefaultDateRange(startDate, endDate);
            List<ShelterCount> shelterStats = jdbc.query("""
                    SELECT s.address, count(a.id) AS animal_count
                    FROM pawbridge_animal.animals a JOIN pawbridge_animal.shelters s ON s.id=a.shelter_id
                    WHERE a.happen_date BETWEEN :startDate AND :endDate
                    GROUP BY s.id ORDER BY count(a.id) DESC
                    """, parameters(range[0], range[1]), (row, index) -> new ShelterCount(
                    row.getString("address"), row.getObject("animal_count", Long.class)));
            Map<String, Long> regions = new LinkedHashMap<>();
            for (ShelterCount shelter : shelterStats) {
                String address = shelter.address();
                if (address == null || address.isBlank()) continue;
                String rawRegion = address.split(" ")[0];
                regions.merge(normalizeRegion(rawRegion), shelter.count(), Long::sum);
            }
            return regions.entrySet().stream().sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                    .map(entry -> new RegionalCount(entry.getKey(), entry.getValue())).collect(Collectors.toList());
        });
    }

    @Override
    public List<DailyCount> dailyAnimals(LocalDate startDate, LocalDate endDate) {
        return reads.execute(ignored -> daily("CAST(a.created_at + INTERVAL '9 hours' AS date)", startDate, endDate));
    }

    @Override
    public IntakeTrend intakeTrend(LocalDate startDate, LocalDate endDate) {
        return reads.execute(ignored -> {
            if (startDate == null || endDate == null || startDate.isAfter(endDate)
                    || endDate.isAfter(LocalDate.now(clock)) || ChronoUnit.DAYS.between(startDate, endDate) >= 366) {
                throw new IllegalArgumentException("오늘까지 최대 366일의 올바른 조회 기간이 필요합니다.");
            }
            LocalDate previousDate = startDate.minusDays(1);
            List<DailyCount> rows = daily("a.happen_date", previousDate, endDate);
            long previous = rows.stream().filter(row -> row.date().equals(previousDate)).mapToLong(DailyCount::count).sum();
            return new IntakeTrend(startDate, endDate,
                    rows.stream().filter(row -> !row.date().isBefore(startDate)).toList(), previous);
        });
    }

    private List<DailyCount> daily(String expression, LocalDate startDate, LocalDate endDate) {
        return jdbc.query("SELECT " + expression + " AS stat_date,count(*) AS animal_count FROM pawbridge_animal.animals a WHERE "
                + expression + " BETWEEN :startDate AND :endDate GROUP BY " + expression + " ORDER BY " + expression,
                parameters(startDate, endDate), (row, index) -> new DailyCount(
                        row.getObject("stat_date", LocalDate.class), row.getObject("animal_count", Long.class)));
    }

    private LocalDate[] resolveDefaultDateRange(LocalDate startDate, LocalDate endDate) {
        LocalDate end = endDate != null ? endDate : LocalDate.now(clock);
        LocalDate start = startDate != null ? startDate : end.minusDays(29);
        return new LocalDate[]{start, end};
    }

    private static MapSqlParameterSource parameters(LocalDate start, LocalDate end) {
        return new MapSqlParameterSource("startDate", start).addValue("endDate", end);
    }

    /** Preserve original first-token/contains mapping and its precedence, including unknown tokens. */
    private static String normalizeRegion(String rawRegion) {
        if (rawRegion == null) return "기타";
        if (rawRegion.contains("서울")) return "서울";
        if (rawRegion.contains("부산")) return "부산";
        if (rawRegion.contains("대구")) return "대구";
        if (rawRegion.contains("인천")) return "인천";
        if (rawRegion.contains("광주")) return "광주";
        if (rawRegion.contains("대전")) return "대전";
        if (rawRegion.contains("울산")) return "울산";
        if (rawRegion.contains("세종")) return "세종";
        if (rawRegion.contains("경기")) return "경기";
        if (rawRegion.contains("강원")) return "강원";
        if (rawRegion.contains("충청북도") || rawRegion.contains("충북")) return "충북";
        if (rawRegion.contains("충청남도") || rawRegion.contains("충남")) return "충남";
        if (rawRegion.contains("전라북도") || rawRegion.contains("전북")) return "전북";
        if (rawRegion.contains("전라남도") || rawRegion.contains("전남")) return "전남";
        if (rawRegion.contains("경상북도") || rawRegion.contains("경북")) return "경북";
        if (rawRegion.contains("경상남도") || rawRegion.contains("경남")) return "경남";
        if (rawRegion.contains("제주")) return "제주";
        return rawRegion;
    }

    private record ShelterCount(String address, Long count) { }
}
