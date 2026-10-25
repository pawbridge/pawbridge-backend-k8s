package com.pawbridge.backend.community.internal.visit.infrastructure

import com.pawbridge.backend.community.internal.visit.application.TodayVisits
import com.pawbridge.backend.community.internal.visit.application.VisitCollection
import com.pawbridge.backend.community.internal.visit.application.VisitCollection.Day
import com.pawbridge.backend.community.internal.visit.application.VisitCollection.Period
import com.pawbridge.backend.community.internal.visit.application.VisitCollectionRejectedException
import com.pawbridge.backend.community.internal.visit.application.VisitCollectionStore
import com.pawbridge.backend.community.internal.visit.application.VisitIdentity
import java.time.Clock
import java.time.LocalDate
import java.time.LocalTime
import java.time.temporal.ChronoUnit
import org.springframework.transaction.support.TransactionOperations

class JdbcVisitCollection(
    private val repository: VisitCollectionStore,
    private val cookies: VisitIdentity,
    private val clock: Clock,
    private val transactions: TransactionOperations,
) : VisitCollection {

    override fun start() {
        repository.start(clock.instant())
    }

    override fun count(cookie: String?): TodayVisits {
        val day = cookies.day(clock.instant())
        val identity = cookies.identity(cookie, day)
        if (identity == null) {
            throw expired()
        }
        return transactions.execute {
            repository.lockDay(day, false)
            // Reject a request that waited across midnight, including a late cleanup race.
            if (day != cookies.day(clock.instant())) {
                throw expired()
            }
            repository.count(day, cookies.hash(day, identity))
            TodayVisits(day, repository.today(day))
        }!!
    }

    override fun period(start: LocalDate?, end: LocalDate?): Period {
        val today = cookies.day(clock.instant())
        if (start == null ||
            end == null ||
            end.isBefore(start) ||
            end.isAfter(today) ||
            ChronoUnit.DAYS.between(start, end) >= 366
        ) {
            throw VisitCollectionRejectedException(VisitCollectionRejectedException.Reason.RANGE)
        }
        val began = repository.startedAt()
        val first = began.atZone(VisitIdentity.ZONE).toLocalDate()
        val counts = HashMap<LocalDate, Long>()
        for (row in repository.period(start, end)) {
            counts[row.date] = row.count
        }
        val days = ArrayList<Day>()
        var date = if (start.isBefore(first)) first else start
        while (!date.isAfter(end)) {
            days.add(Day(date, counts.getOrDefault(date, 0L)))
            date = date.plusDays(1)
        }
        return Period(start, end, today, began, java.util.List.copyOf(days))
    }

    override fun cleanup() {
        val now = clock.instant().atZone(VisitIdentity.ZONE)
        if (now.toLocalTime().isBefore(LocalTime.of(0, 5))) {
            return
        }
        for (date in repository.expiredDays(now.toLocalDate())) {
            transactions.executeWithoutResult {
                repository.lockDay(date, true)
                repository.deleteDay(date)
            }
        }
    }

    private fun expired(): VisitCollectionRejectedException =
        VisitCollectionRejectedException(VisitCollectionRejectedException.Reason.COOKIE)
}
