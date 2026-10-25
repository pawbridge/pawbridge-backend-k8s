package com.pawbridge.backend.user.internal.shelter.infrastructure

import com.pawbridge.backend.user.internal.shelter.application.ShelterMembership
import com.pawbridge.backend.user.internal.shelter.application.ShelterMembership.AdminApplication
import com.pawbridge.backend.user.internal.shelter.application.ShelterMembership.Application
import com.pawbridge.backend.user.internal.shelter.application.ShelterMembership.Daily
import com.pawbridge.backend.user.internal.shelter.application.ShelterMembership.Member
import com.pawbridge.backend.user.internal.shelter.application.ShelterMembership.MissingShelter
import com.pawbridge.backend.user.internal.shelter.application.ShelterMembership.Page
import com.pawbridge.backend.user.internal.shelter.application.ShelterMembership.Shelter
import com.pawbridge.backend.user.internal.shelter.application.ShelterMembership.ShelterLookup
import com.pawbridge.backend.user.internal.shelter.application.ShelterMembership.Statistics
import com.pawbridge.backend.user.internal.shelter.application.ShelterMembership.Status
import com.pawbridge.backend.user.internal.shelter.application.ShelterMembershipRejectedException
import com.pawbridge.backend.user.internal.shelter.application.ShelterMembershipRejectedException.Reason
import org.springframework.dao.InvalidDataAccessApiUsageException
import org.springframework.dao.OptimisticLockingFailureException
import org.springframework.jdbc.core.JdbcOperations
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.support.SqlValue
import org.springframework.transaction.support.TransactionOperations
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Clock
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.Calendar
import java.util.TimeZone
import java.util.function.Supplier

class JdbcShelterMembership(
    private val jdbc: JdbcOperations,
    private val write: TransactionOperations,
    private val read: TransactionOperations,
    private val statistics: TransactionOperations,
    private val shelters: ShelterLookup,
    clock: Clock,
    private val now: Supplier<LocalDateTime?>,
) : ShelterMembership {

    // Field names are read by boot configuration tests through reflection (jdbc, write, read, statistics, clock).
    private val clock: Clock = clock.withZone(ZoneId.of("Asia/Seoul"))

    constructor(
        jdbc: JdbcOperations,
        write: TransactionOperations,
        read: TransactionOperations,
        statistics: TransactionOperations,
        shelters: ShelterLookup,
        clock: Clock,
    ) : this(jdbc, write, read, statistics, shelters, clock, Supplier { LocalDateTime.now() })

    override fun submit(currentUserId: Long, shelterName: String?): Application {
        return write.execute {
            val user = user(currentUserId, true) ?: throw rejected(Reason.TOKEN_INVALID)
            if ("ROLE_USER" != user.role) {
                throw rejected(Reason.FORBIDDEN)
            }
            if (count(COUNT_PENDING_OF_USER, currentUserId) > 0) {
                throw rejected(Reason.CONFLICT)
            }
            val stripped = text(shelterName, 100)
            val requested = now.get()
            val id: Long? = jdbc.queryForObject(
                INSERT_APPLICATION,
                Long::class.javaObjectType,
                currentUserId,
                stripped,
                utcTimestamp(requested),
            )
            Application(id, currentUserId, stripped, Status.PENDING, requested, null, null, null, null)
        }!!
    }

    override fun mine(currentUserId: Long, page: Int, size: Int): Page<Application> {
        return read.execute {
            authenticated(currentUserId)
            checkPage(page, size)
            applications(" WHERE user_id=?", arrayOf<Any?>(currentUserId), page, size).map { it.view() }
        }!!
    }

    override fun list(currentUserId: Long, status: Status?, page: Int, size: Int): Page<AdminApplication> {
        return read.execute {
            administrator(currentUserId)
            checkPage(page, size)
            val rows = applications(
                if (status == null) "" else " WHERE status=?",
                if (status == null) arrayOf() else arrayOf<Any?>(status.name),
                page,
                size,
            )
            var applicants: List<User> = listOf()
            if (rows.content.isNotEmpty()) {
                val marks = java.util.Collections.nCopies(rows.content.size, "?").joinToString(",")
                applicants = jdbc.query(
                    "SELECT user_id,name,email,role FROM pawbridge_user.users WHERE user_id IN ($marks)",
                    USER,
                    *rows.content.map { it.userId }.toTypedArray(),
                )
            }
            val found = applicants
            rows.map { row -> admin(row, found.firstOrNull { it.id == row.userId }) }
        }!!
    }

    override fun detail(currentUserId: Long, id: Long): AdminApplication {
        return read.execute {
            administrator(currentUserId)
            val row = find(id, false) ?: throw rejected(Reason.NOT_FOUND)
            admin(row, user(row.userId, false))
        }!!
    }

    override fun approve(currentUserId: Long, id: Long, careRegNo: String?, note: String?): AdminApplication {
        return write.execute {
            val reviewer = administrator(currentUserId)
            val care = text(careRegNo, 50)
            val review = text(note, 1000)
            val locked = lock(id)
            if ("ROLE_USER" != locked.user.role) {
                throw rejected(Reason.CONFLICT)
            }
            val shelter: Shelter?
            try {
                shelter = shelters.find(care)
            } catch (missing: MissingShelter) {
                throw rejected(Reason.SHELTER_NOT_FOUND)
            } catch (unavailable: RuntimeException) {
                throw rejected(Reason.SHELTER_UNAVAILABLE)
            }
            if (shelter == null || shelter.id == null || care != shelter.careRegNo) {
                throw rejected(Reason.SHELTER_UNAVAILABLE)
            }
            val reviewed = now.get()
            val updated = review(locked.row, Status.APPROVED, reviewer.id, reviewed, review, care)
            if (jdbc.update(
                    APPROVE_APPLICATION,
                    reviewer.id,
                    utcTimestamp(reviewed),
                    review,
                    care,
                    id,
                ) != 1
            ) {
                throw stale()
            }
            // JPA auditing runs only for the changed user; nickname/password remain untouched.
            if (jdbc.update(PROMOTE_USER, care, utcTimestamp(now.get()), locked.user.id) != 1) {
                throw stale()
            }
            admin(updated, locked.user)
        }!!
    }

    override fun reject(currentUserId: Long, id: Long, reason: String?): AdminApplication {
        return write.execute {
            val reviewer = administrator(currentUserId)
            val locked = lock(id)
            val review = text(reason, 1000)
            val reviewed = now.get()
            val updated = review(locked.row, Status.REJECTED, reviewer.id, reviewed, review, locked.row.careRegNo)
            if (jdbc.update(REJECT_APPLICATION, reviewer.id, utcTimestamp(reviewed), review, id) != 1) {
                throw stale()
            }
            admin(updated, user(locked.row.userId, false))
        }!!
    }

    override fun members(currentUserId: Long, careRegNo: String?, page: Int, size: Int): Page<Member> {
        return read.execute {
            administrator(currentUserId)
            val care = text(careRegNo, 50)
            checkPage(page, size)
            val users = jdbc.query(SELECT_MEMBERS, USER, care, size, offset(page, size))
            val total = total(users.size, page, size) { count(COUNT_MEMBERS, care) }
            val content = ArrayList<Member>()
            for (user in users) {
                val approvals = jdbc.query(
                    SELECT_LAST_APPROVAL,
                    RowMapper { rs, _ -> rs.getLong("id") },
                    user.id,
                    care,
                )
                content.add(Member(user.id, user.name, user.email, approvals.firstOrNull()))
            }
            Page(content, total, page, size)
        }!!
    }

    override fun statistics(currentUserId: Long, startDate: LocalDate?, endDate: LocalDate?): Statistics {
        return statistics.execute {
            administrator(currentUserId)
            if (startDate == null || endDate == null || startDate.isAfter(endDate) ||
                endDate.isAfter(LocalDate.now(clock)) || ChronoUnit.DAYS.between(startDate, endDate) >= 366
            ) {
                throw rejected(Reason.INVALID_INPUT)
            }
            val previous = startDate.minusDays(1)
            val daily = jdbc.query(
                SELECT_DAILY,
                RowMapper { rs, _ -> Daily(rs.getObject("requested_date", LocalDate::class.java), rs.getLong("requested_count")) },
                utcTimestamp(previous.atStartOfDay()),
                utcTimestamp(endDate.plusDays(1).atStartOfDay()),
            )
            val reviews = jdbc.query(
                SELECT_REVIEWS,
                RowMapper { rs, _ -> ReviewCount(Status.valueOf(rs.getString("status")), rs.getLong("review_count")) },
                utcTimestamp(startDate.atStartOfDay()),
                utcTimestamp(endDate.plusDays(1).atStartOfDay()),
            )
            Statistics(
                startDate,
                endDate,
                daily.filter { !it.date!!.isBefore(startDate) },
                daily.filter { it.date!! == previous }.sumOf { it.count },
                count(COUNT_PENDING),
                reviews.filter { it.status == Status.APPROVED }.sumOf { it.count },
                reviews.filter { it.status == Status.REJECTED }.sumOf { it.count },
            )
        }!!
    }

    private fun authenticated(currentUserId: Long): User =
        user(currentUserId, false) ?: throw rejected(Reason.TOKEN_INVALID)

    private fun administrator(currentUserId: Long): User {
        val found = authenticated(currentUserId)
        if ("ROLE_ADMIN" != found.role) {
            throw rejected(Reason.FORBIDDEN)
        }
        return found
    }

    private fun user(id: Long, lock: Boolean): User? {
        val rows = jdbc.query(
            "SELECT user_id,name,email,role FROM pawbridge_user.users WHERE user_id=?" +
                (if (lock) " FOR UPDATE" else ""),
            USER,
            id,
        )
        return rows.firstOrNull()
    }

    private fun find(id: Long, lock: Boolean): Row? {
        val rows = jdbc.query(
            "SELECT $COLUMNS FROM pawbridge_user.shelter_applications WHERE id=?" + (if (lock) " FOR UPDATE" else ""),
            APPLICATION,
            id,
        )
        return rows.firstOrNull()
    }

    private fun lock(id: Long): Locked {
        val first = find(id, false) ?: throw rejected(Reason.NOT_FOUND)
        val applicant = user(first.userId, true) ?: throw rejected(Reason.NOT_FOUND)
        val refreshed = find(id, true) ?: throw stale()
        if (refreshed.status != Status.PENDING) {
            throw rejected(Reason.CONFLICT)
        }
        return Locked(refreshed, applicant)
    }

    private fun applications(where: String, args: Array<Any?>, number: Int, size: Int): Rows {
        val pageArgs = args.copyOf(args.size + 2)
        pageArgs[args.size] = size
        pageArgs[args.size + 1] = offset(number, size)
        val rows = jdbc.query(
            "SELECT $COLUMNS FROM pawbridge_user.shelter_applications$where ORDER BY id DESC LIMIT ? OFFSET ?",
            APPLICATION,
            *pageArgs,
        )
        return Rows(
            rows,
            total(rows.size, number, size) { count("SELECT count(*) FROM pawbridge_user.shelter_applications$where", *args) },
            number,
            size,
        )
    }

    // `!!` keeps the NullPointerException of the Java auto-unboxing when the query returns no value.
    private fun count(sql: String, vararg args: Any?): Long = jdbc.queryForObject(sql, Long::class.javaObjectType, *args)!!

    private class User(val id: Long, val name: String?, val email: String?, val role: String?)

    private class Locked(val row: Row, val user: User)

    private class ReviewCount(val status: Status, val count: Long)

    private class Row(
        val id: Long,
        val userId: Long,
        val shelterName: String?,
        val status: Status,
        val requestedAt: LocalDateTime?,
        val reviewedBy: Long?,
        val reviewedAt: LocalDateTime?,
        val reviewNote: String?,
        val careRegNo: String?,
    ) {
        fun view(): Application = Application(
            id,
            userId,
            shelterName,
            status,
            requestedAt,
            reviewedAt,
            reviewedBy,
            if (status == Status.REJECTED) reviewNote else null,
            careRegNo,
        )
    }

    private class Rows(
        val content: List<Row>,
        val total: Long,
        val number: Int,
        val size: Int,
    ) {
        fun <T> map(mapper: (Row) -> T): Page<T> = Page(content.map(mapper), total, number, size)
    }

    private companion object {
        private const val COLUMNS =
            "id,user_id,shelter_name,status,requested_at,reviewed_by,reviewed_at,review_note,care_reg_no"

        private const val COUNT_PENDING_OF_USER =
            "SELECT count(*) FROM pawbridge_user.shelter_applications WHERE user_id=? AND status='PENDING'"
        private const val INSERT_APPLICATION =
            "INSERT INTO pawbridge_user.shelter_applications(user_id,shelter_name,status,requested_at) " +
                "VALUES(?,?,'PENDING',?) RETURNING id"
        private const val APPROVE_APPLICATION =
            "UPDATE pawbridge_user.shelter_applications " +
                "SET status='APPROVED',reviewed_by=?,reviewed_at=?,review_note=?,care_reg_no=? WHERE id=?"
        private const val PROMOTE_USER =
            "UPDATE pawbridge_user.users SET role='ROLE_SHELTER',care_reg_no=?,updated_at=? WHERE user_id=?"
        private const val REJECT_APPLICATION =
            "UPDATE pawbridge_user.shelter_applications " +
                "SET status='REJECTED',reviewed_by=?,reviewed_at=?,review_note=? WHERE id=?"
        private const val SELECT_MEMBERS =
            "SELECT user_id,name,email,role FROM pawbridge_user.users " +
                "WHERE care_reg_no=? AND role='ROLE_SHELTER' ORDER BY user_id DESC LIMIT ? OFFSET ?"
        private const val COUNT_MEMBERS =
            "SELECT count(*) FROM pawbridge_user.users WHERE care_reg_no=? AND role='ROLE_SHELTER'"
        private const val SELECT_LAST_APPROVAL =
            "SELECT id FROM pawbridge_user.shelter_applications " +
                "WHERE user_id=? AND care_reg_no=? AND status='APPROVED' ORDER BY id DESC LIMIT 1"
        private const val SELECT_DAILY =
            "SELECT CAST(requested_at+INTERVAL '9 hours' AS date) AS requested_date,count(*) AS requested_count " +
                "FROM pawbridge_user.shelter_applications WHERE requested_at>=? AND requested_at<? " +
                "GROUP BY CAST(requested_at+INTERVAL '9 hours' AS date) " +
                "ORDER BY CAST(requested_at+INTERVAL '9 hours' AS date)"
        private const val SELECT_REVIEWS =
            "SELECT status,count(*) AS review_count FROM pawbridge_user.shelter_applications " +
                "WHERE reviewed_at>=? AND reviewed_at<? AND status IN ('APPROVED','REJECTED') GROUP BY status"
        private const val COUNT_PENDING = "SELECT count(*) FROM pawbridge_user.shelter_applications WHERE status='PENDING'"

        private val USER = RowMapper { rs, _ ->
            User(rs.getLong("user_id"), rs.getString("name"), rs.getString("email"), rs.getString("role"))
        }

        private val APPLICATION = RowMapper { rs, _ ->
            Row(
                rs.getLong("id"),
                rs.getLong("user_id"),
                rs.getString("shelter_name"),
                Status.valueOf(rs.getString("status")),
                time(rs, "requested_at"),
                rs.getObject("reviewed_by", Long::class.javaObjectType),
                time(rs, "reviewed_at"),
                rs.getString("review_note"),
                rs.getString("care_reg_no"),
            )
        }

        private fun total(rows: Int, number: Int, size: Int, count: () -> Long): Long =
            if (rows < size && (number == 0 || rows > 0)) offset(number, size) + rows else count()

        private fun offset(number: Int, size: Int): Long = number.toLong() * size

        private fun checkPage(number: Int, size: Int) {
            if (number < 0 || size < 1 || size > 100) {
                throw rejected(Reason.INVALID)
            }
            if (offset(number, size) > Integer.MAX_VALUE) {
                throw InvalidDataAccessApiUsageException("Page offset exceeds the supported range")
            }
        }

        // Java String.isBlank/strip semantics (Character.isWhitespace): Kotlin's isBlank/trim would also treat
        // NBSP, U+2007 and U+202F as whitespace and change which names and numbers are accepted.
        private fun text(value: String?, max: Int): String {
            if (value == null || value.all { Character.isWhitespace(it) } || strip(value).length > max) {
                throw rejected(Reason.INVALID)
            }
            return strip(value)
        }

        private fun strip(value: String): String {
            var start = 0
            var end = value.length
            while (start < end && Character.isWhitespace(value[start])) {
                start++
            }
            while (end > start && Character.isWhitespace(value[end - 1])) {
                end--
            }
            return value.substring(start, end)
        }

        private fun rejected(reason: Reason): ShelterMembershipRejectedException =
            ShelterMembershipRejectedException(reason)

        private fun stale(): RuntimeException =
            OptimisticLockingFailureException("Shelter membership row changed concurrently")

        private fun utc(): Calendar = Calendar.getInstance(TimeZone.getTimeZone("UTC"))

        private fun utcTimestamp(time: LocalDateTime?): SqlValue = object : SqlValue {
            override fun setValue(statement: PreparedStatement, index: Int) {
                statement.setTimestamp(index, Timestamp.valueOf(time), utc())
            }

            override fun cleanup() {
            }
        }

        private fun time(rs: ResultSet, column: String): LocalDateTime? {
            val value: Timestamp? = rs.getTimestamp(column, utc())
            return value?.toLocalDateTime()
        }

        private fun review(
            row: Row,
            status: Status,
            reviewer: Long,
            at: LocalDateTime?,
            note: String?,
            care: String?,
        ): Row = Row(row.id, row.userId, row.shelterName, status, row.requestedAt, reviewer, at, note, care)

        private fun admin(row: Row, user: User?): AdminApplication = AdminApplication(
            row.view(),
            if (user == null) "탈퇴 회원" else user.name,
            user?.email,
            row.reviewNote,
        )
    }
}
