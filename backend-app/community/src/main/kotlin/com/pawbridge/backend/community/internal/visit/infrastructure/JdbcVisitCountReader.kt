package com.pawbridge.backend.community.internal.visit.infrastructure

import com.pawbridge.backend.community.internal.visit.application.VisitCountReader
import java.sql.Date
import java.time.LocalDate
import org.springframework.jdbc.core.JdbcOperations
import org.springframework.transaction.support.TransactionOperations

class JdbcVisitCountReader(
    private val jdbc: JdbcOperations,
    private val readOnlyTransaction: TransactionOperations,
) : VisitCountReader {

    override fun count(day: LocalDate): Long =
        readOnlyTransaction.execute {
            val rows = jdbc.queryForList(READ_COUNT_SQL, Long::class.javaObjectType, Date.valueOf(day))
            if (rows.isEmpty()) 0L else rows[0]
        }!!

    private companion object {
        private val READ_COUNT_SQL = """
            SELECT unique_visitors FROM pawbridge_community.daily_visit_count WHERE visit_date = ?
        """.trimIndent() + "\n"
    }
}
