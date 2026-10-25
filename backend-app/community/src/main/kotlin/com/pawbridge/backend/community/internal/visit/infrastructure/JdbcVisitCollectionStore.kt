package com.pawbridge.backend.community.internal.visit.infrastructure

import com.pawbridge.backend.community.internal.visit.application.VisitCollection.Day
import com.pawbridge.backend.community.internal.visit.application.VisitCollectionStore
import java.sql.Date
import java.sql.Timestamp
import java.time.Instant
import java.time.LocalDate
import org.springframework.jdbc.core.JdbcOperations
import org.springframework.jdbc.core.RowCallbackHandler
import org.springframework.jdbc.core.RowMapper

class JdbcVisitCollectionStore(private val jdbc: JdbcOperations) : VisitCollectionStore {

    override fun start(instant: Instant) {
        jdbc.update(
            "INSERT INTO pawbridge_community.visit_collection(singleton,started_at) VALUES(true,?) ON CONFLICT DO NOTHING",
            Timestamp.from(instant),
        )
    }

    override fun startedAt(): Instant =
        jdbc.queryForObject(
            "SELECT started_at FROM pawbridge_community.visit_collection WHERE singleton",
            RowMapper { row, _ -> row.getTimestamp(1).toInstant() },
        )!!

    // Shared with writers; cleanup takes the exclusive form. Must execute inside a transaction.
    override fun lockDay(day: LocalDate, exclusive: Boolean) {
        jdbc.query(
            "SELECT pg_advisory_xact_lock" + (if (exclusive) "" else "_shared") + "(?,?)",
            RowCallbackHandler { },
            LOCK_NAMESPACE,
            Math.toIntExact(day.toEpochDay()),
        )
    }

    override fun count(day: LocalDate, hash: String) {
        jdbc.update(
            """
            WITH inserted AS (
              INSERT INTO pawbridge_community.daily_visitor(visit_date,visitor_hash) VALUES (?,?)
              ON CONFLICT (visit_date,visitor_hash) DO NOTHING RETURNING visit_date
            )
            INSERT INTO pawbridge_community.daily_visit_count(visit_date,unique_visitors)
            SELECT visit_date,1 FROM inserted
            ON CONFLICT (visit_date) DO UPDATE
              SET unique_visitors = pawbridge_community.daily_visit_count.unique_visitors + 1
            """.trimIndent() + "\n",
            Date.valueOf(day),
            hash,
        )
    }

    override fun today(day: LocalDate): Long {
        val rows = jdbc.queryForList(
            "SELECT unique_visitors FROM pawbridge_community.daily_visit_count WHERE visit_date=?",
            Long::class.javaObjectType,
            Date.valueOf(day),
        )
        return if (rows.isEmpty()) 0L else rows[0]
    }

    override fun period(start: LocalDate, end: LocalDate): List<Day> =
        jdbc.query(
            "SELECT visit_date,unique_visitors FROM pawbridge_community.daily_visit_count WHERE visit_date BETWEEN ? AND ? ORDER BY visit_date",
            RowMapper { row, _ -> Day(row.getDate(1).toLocalDate(), row.getLong(2)) },
            Date.valueOf(start),
            Date.valueOf(end),
        )

    override fun expiredDays(today: LocalDate): List<LocalDate> =
        jdbc.query(
            "SELECT DISTINCT visit_date FROM pawbridge_community.daily_visitor WHERE visit_date < ? ORDER BY visit_date",
            RowMapper { row, _ -> row.getDate(1).toLocalDate() },
            Date.valueOf(today),
        )

    override fun deleteDay(day: LocalDate) {
        jdbc.update("DELETE FROM pawbridge_community.daily_visitor WHERE visit_date=?", Date.valueOf(day))
    }

    private companion object {
        private const val LOCK_NAMESPACE = 736241
    }
}
