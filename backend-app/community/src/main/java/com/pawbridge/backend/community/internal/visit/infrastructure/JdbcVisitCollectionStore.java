package com.pawbridge.backend.community.internal.visit.infrastructure;

import com.pawbridge.backend.community.internal.visit.application.VisitCollection.Day;
import com.pawbridge.backend.community.internal.visit.application.VisitCollectionStore;
import java.sql.Date;
import java.time.*;
import java.util.List;
import org.springframework.jdbc.core.JdbcOperations;

public final class JdbcVisitCollectionStore implements VisitCollectionStore {
    private static final int LOCK_NAMESPACE = 736241;
    private final JdbcOperations jdbc;
    public JdbcVisitCollectionStore(JdbcOperations jdbc) { this.jdbc = jdbc; }

    public void start(Instant instant) {
        jdbc.update("INSERT INTO pawbridge_community.visit_collection(singleton,started_at) VALUES(true,?) ON CONFLICT DO NOTHING",
                java.sql.Timestamp.from(instant));
    }
    public Instant startedAt() {
        return jdbc.queryForObject("SELECT started_at FROM pawbridge_community.visit_collection WHERE singleton",
                (row, n) -> row.getTimestamp(1).toInstant());
    }
    // Shared with writers; cleanup takes the exclusive form. Must execute inside a transaction.
    public void lockDay(LocalDate day, boolean exclusive) {
        jdbc.query("SELECT pg_advisory_xact_lock" + (exclusive ? "" : "_shared") + "(?,?)",
                row -> {}, LOCK_NAMESPACE, Math.toIntExact(day.toEpochDay()));
    }
    public void count(LocalDate day, String hash) {
        jdbc.update("""
                WITH inserted AS (
                  INSERT INTO pawbridge_community.daily_visitor(visit_date,visitor_hash) VALUES (?,?)
                  ON CONFLICT (visit_date,visitor_hash) DO NOTHING RETURNING visit_date
                )
                INSERT INTO pawbridge_community.daily_visit_count(visit_date,unique_visitors)
                SELECT visit_date,1 FROM inserted
                ON CONFLICT (visit_date) DO UPDATE
                  SET unique_visitors = pawbridge_community.daily_visit_count.unique_visitors + 1
                """, Date.valueOf(day), hash);
    }
    public long today(LocalDate day) {
        List<Long> rows = jdbc.queryForList("SELECT unique_visitors FROM pawbridge_community.daily_visit_count WHERE visit_date=?",
                Long.class, Date.valueOf(day));
        return rows.isEmpty() ? 0 : rows.get(0);
    }
    public List<Day> period(LocalDate start, LocalDate end) {
        return jdbc.query("SELECT visit_date,unique_visitors FROM pawbridge_community.daily_visit_count WHERE visit_date BETWEEN ? AND ? ORDER BY visit_date",
                (row, n) -> new Day(row.getDate(1).toLocalDate(), row.getLong(2)), Date.valueOf(start), Date.valueOf(end));
    }
    public List<LocalDate> expiredDays(LocalDate today) {
        return jdbc.query("SELECT DISTINCT visit_date FROM pawbridge_community.daily_visitor WHERE visit_date < ? ORDER BY visit_date",
                (row,n) -> row.getDate(1).toLocalDate(), Date.valueOf(today));
    }
    public void deleteDay(LocalDate day) {
        jdbc.update("DELETE FROM pawbridge_community.daily_visitor WHERE visit_date=?", Date.valueOf(day));
    }
}
