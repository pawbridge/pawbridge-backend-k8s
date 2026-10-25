package com.pawbridge.backend.user.internal.shelter.infrastructure

import com.pawbridge.backend.user.internal.shelter.application.ShelterMembership
import com.pawbridge.backend.user.internal.shelter.application.ShelterMembership.Daily
import com.pawbridge.backend.user.internal.shelter.application.ShelterMembership.MissingShelter
import com.pawbridge.backend.user.internal.shelter.application.ShelterMembership.Shelter
import com.pawbridge.backend.user.internal.shelter.application.ShelterMembership.Status
import com.pawbridge.backend.user.internal.shelter.application.ShelterMembershipRejectedException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.mockito.Mockito.verify
import org.springframework.dao.OptimisticLockingFailureException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.support.SqlValue
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.TransactionSystemException
import org.springframework.transaction.support.AbstractPlatformTransactionManager
import org.springframework.transaction.support.DefaultTransactionStatus
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.transaction.support.TransactionTemplate
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.SQLException
import java.sql.Timestamp
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.Calendar
import java.util.Collections
import java.util.function.Supplier

class JdbcShelterMembershipTest {
    @ParameterizedTest
    @ValueSource(strings = ["ROLE_USER", "ROLE_ADMIN", "ROLE_SHELTER"])
    fun given__a_current_role__when__submitting__then__lock_before_role_pending_text_and_insert(role: String) {
        val f = Fixture()
        f.jdbc.actorRole = role
        if (role == "ROLE_USER") {
            val result = f.service.submit(7, "  보호소  ")
            assertEquals("보호소", result.shelterName)
            assertEquals(Status.PENDING, result.status)
            assertEquals(91L, result.id)
            assertEquals(listOf("begin", "actor-lock", "count", "now", "insert", "commit", "cleanup"), f.events)
            assertEquals(1, f.nowCalls)
        } else {
            assertReason("FORBIDDEN") { f.service.submit(7, " ") }
            assertEquals(listOf("begin", "actor-lock", "rollback", "cleanup"), f.events)
        }
    }

    @Test
    fun given__a_pending_request__when__submitting_invalid_text__then__preserve_conflict_before_text_validation() {
        val f = Fixture()
        f.jdbc.pending = 1

        assertReason("CONFLICT") { f.service.submit(7, " ") }

        assertEquals(0, f.nowCalls)
        assertFalse(f.events.contains("insert"))
    }

    @Test
    fun given__a_pending_applicant__when__approving__then__lock_user_before_refreshed_application_and_call_animal_inside_the_same_transaction() {
        val f = Fixture()

        val result = f.service.approve(1, 91, " reg ", " 승인 사유 ")

        assertEquals(Status.APPROVED, result.application!!.status)
        assertNull(result.application!!.reason)
        assertEquals("승인 사유", result.reviewNote)
        assertEquals("reg", result.application!!.careRegNo)
        assertEquals(
            listOf(
                "begin", "actor", "application", "applicant-lock", "application-lock", "animal", "now",
                "application-update", "now", "user-update", "commit", "cleanup",
            ),
            f.events,
        )
        assertEquals(2, f.nowCalls)
        assertEquals(2, f.jdbc.writes.size)
        assertFalse(f.jdbc.writes[1].contains("nickname"))
        assertFalse(f.jdbc.writes[1].contains("password"))
    }

    @ParameterizedTest
    @ValueSource(strings = ["processed", "role", "missing", "vanished"])
    fun given__a_refreshed_state_or_missing_applicant__when__approving__then__stop_before_animal_and_any_write(state: String) {
        val f = Fixture()
        f.jdbc.state = state

        if (state == "vanished") {
            assertThrows(OptimisticLockingFailureException::class.java) { f.service.approve(1, 91, "reg", "note") }
        } else {
            assertReason(if (state == "missing") "NOT_FOUND" else "CONFLICT") { f.service.approve(1, 91, "reg", "note") }
        }

        assertFalse(f.events.contains("animal"))
        assertTrue(f.jdbc.writes.isEmpty())
        assertTrue(f.events.contains("rollback"))
    }

    @ParameterizedTest
    @ValueSource(strings = ["404", "runtime", "null", "wrong"])
    fun given__animal_failure_or_invalid_identity__when__approving__then__return_only_original_business_failure_without_writes(
        phase: String,
    ) {
        val f = Fixture()
        f.animal = phase

        assertReason(if (phase == "404") "SHELTER_NOT_FOUND" else "SHELTER_UNAVAILABLE") {
            f.service.approve(1, 91, "reg", "note")
        }

        assertTrue(f.jdbc.writes.isEmpty())
        assertEquals(0, f.nowCalls)
    }

    @Test
    fun given__a_pending_request__when__rejecting__then__validate_reason_after_locks_and_leave_user_role_untouched() {
        val invalid = Fixture()
        assertReason("INVALID") { invalid.service.reject(1, 91, " ") }
        assertTrue(invalid.events.contains("application-lock"))
        assertTrue(invalid.jdbc.writes.isEmpty())
        val f = Fixture()

        val result = f.service.reject(1, 91, " 이유 ")

        assertEquals("이유", result.application!!.reason)
        assertEquals(Status.REJECTED, result.application!!.status)
        assertEquals(1, f.jdbc.writes.size)
        assertFalse(f.events.contains("animal"))
        assertFalse(f.events.contains("user-update"))
    }

    @Test
    fun given__a_changed_database_admin_role__when__calling_admin_operations__then__reject_before_target_or_input_queries() {
        val f = Fixture()
        f.jdbc.actorRole = "ROLE_USER"

        assertReason("FORBIDDEN") { f.service.approve(7, 91, " ", " ") }

        assertEquals(listOf("begin", "actor", "rollback", "cleanup"), f.events)
    }

    @Test
    fun given__an_own_page_for_any_existing_role__when__listing__then__authenticate_before_page_validation_and_map_without_admin_requirement() {
        for (role in listOf("ROLE_USER", "ROLE_ADMIN", "ROLE_SHELTER")) {
            val f = Fixture()
            f.jdbc.actorRole = role
            val result = f.service.mine(7, 0, 20)
            assertEquals(1L, result.total)
            assertEquals(1, result.content.size)
            assertEquals(listOf("begin", "actor", "application-page", "commit", "cleanup"), f.events)
        }
        val missing = Fixture()
        missing.jdbc.actorMissing = true

        assertReason("TOKEN_INVALID") { missing.service.mine(7, -1, 0) }
    }

    @Test
    fun given__an_admin_page_and_members__when__enriching__then__keep_one_user_batch_and_each_members_latest_approval_lookup() {
        val f = Fixture()
        assertEquals("신청자", f.service.list(1, null, 0, 20).content.first().applicantName)
        assertEquals(1, Collections.frequency(f.events, "user-batch"))
        val member = Fixture()

        val result = member.service.members(1, " reg ", 0, 20)

        assertEquals(91L, result.content.first().approvalApplicationId)
        assertEquals(1, Collections.frequency(member.events, "latest-approval"))
    }

    @Test
    fun given__a_seoul_day_and_sparse_rows__when__reading_statistics__then__use_repeatable_read_and_preserve_previous_day_current_pending_and_query_order() {
        val f = Fixture()
        val day = LocalDate.parse("2026-10-20")

        val result = f.service.statistics(1, day, day)

        assertEquals(2L, result.previousDayCount)
        assertEquals(listOf(Daily(day, 3)), result.daily)
        assertEquals(4L, result.approvedCount)
        assertEquals(5L, result.rejectedCount)
        assertEquals(listOf("begin", "actor", "daily", "reviews", "count", "commit", "cleanup"), f.events)
        assertTrue(f.manager.readOnly)
        assertEquals(TransactionDefinition.ISOLATION_REPEATABLE_READ, f.manager.isolation)
        val accepted = Fixture()
        accepted.service.statistics(1, day.minusDays(365), day)
    }

    @ParameterizedTest
    @ValueSource(strings = ["366", "future", "reverse", "null"])
    fun given__invalid_statistics_dates__when__querying__then__reject_after_current_admin_before_aggregate_sql(mode: String) {
        val f = Fixture()
        val day = LocalDate.parse("2026-10-20")
        var start: LocalDate? = day
        var end: LocalDate? = day
        when (mode) {
            "366" -> start = day.minusDays(366)
            "future" -> end = day.plusDays(1)
            "reverse" -> start = day.plusDays(1)
            else -> start = null
        }
        val from = start
        val to = end

        assertReason("INVALID_INPUT") { f.service.statistics(1, from, to) }

        assertEquals(listOf("begin", "actor", "rollback", "cleanup"), f.events)
    }

    @ParameterizedTest
    @ValueSource(strings = ["second-write", "commit"])
    fun given__a_late_write_or_commit_failure__when__approving__then__propagate_without_a_partial_response_or_retry(phase: String) {
        val f = Fixture()
        val failure: RuntimeException = TransactionSystemException("synthetic-private")
        if (phase == "commit") {
            f.manager.failure = failure
        } else {
            f.jdbc.failure = failure
        }

        assertSame(failure, assertThrows(RuntimeException::class.java) { f.service.approve(1, 91, "reg", "note") })

        assertEquals(1, Collections.frequency(f.events, "begin"))
        assertEquals("cleanup", f.events.last())
    }

    @Test
    fun given__local_microseconds__when__binding_a_timestamp__then__use_utc_calendar_without_reading_unneeded_user_columns() {
        val f = Fixture()
        f.service.submit(7, "이름")
        val timestamp = f.jdbc.insertArgs!![2] as SqlValue
        val statement = mock(PreparedStatement::class.java)

        timestamp.setValue(statement, 1)

        val calendar = ArgumentCaptor.forClass(Calendar::class.java)
        verify(statement).setTimestamp(eq(1), eq(Timestamp.valueOf(NOW)), calendar.capture())
        assertEquals("UTC", calendar.value.timeZone.id)
    }

    private fun assertReason(reason: String, action: () -> Unit) {
        assertEquals(reason, assertThrows(ShelterMembershipRejectedException::class.java) { action() }.reason().name)
    }

    private class Fixture {
        val events = ArrayList<String>()
        val jdbc = FakeJdbc(events)
        val manager = Manager(events)
        val service: JdbcShelterMembership
        var animal = "ok"
        var nowCalls = 0

        init {
            val tx = TransactionTemplate(manager)
            val read = TransactionTemplate(manager)
            read.isReadOnly = true
            val stats = TransactionTemplate(manager)
            stats.isReadOnly = true
            stats.isolationLevel = TransactionDefinition.ISOLATION_REPEATABLE_READ
            service = JdbcShelterMembership(
                jdbc,
                tx,
                read,
                stats,
                ShelterMembership.ShelterLookup { care ->
                    events.add("animal")
                    assertTrue(TransactionSynchronizationManager.isActualTransactionActive())
                    when (animal) {
                        "404" -> throw MissingShelter()
                        "runtime" -> throw IllegalStateException("private")
                        "null" -> null
                        "wrong" -> Shelter(8L, "other")
                        else -> Shelter(8L, care)
                    }
                },
                Clock.fixed(Instant.parse("2026-10-19T15:30:00Z"), ZoneOffset.UTC),
                Supplier {
                    events.add("now")
                    nowCalls++
                    NOW
                },
            )
        }
    }

    private class FakeJdbc(private val events: MutableList<String>) : JdbcTemplate() {
        val writes = ArrayList<String>()
        var actorRole = "ROLE_USER"
        var state = "ok"
        var actorMissing = false
        var pending = 0L
        var failure: RuntimeException? = null
        var insertArgs: Array<Any?>? = null

        override fun <T> query(sql: String, rowMapper: RowMapper<T>, vararg args: Any?): MutableList<T> {
            try {
                val rs = mock(ResultSet::class.java)
                if (sql.contains("FROM pawbridge_user.users")) {
                    val actor = !sql.contains(" IN (") && !sql.contains("care_reg_no=?") && (args[0] as Number).toLong() != 8L
                    if (sql.contains(" IN (")) {
                        events.add("user-batch")
                    } else if (sql.contains("care_reg_no=?")) {
                        events.add("members")
                    } else {
                        events.add(if (actor) (if (sql.contains("FOR UPDATE")) "actor-lock" else "actor") else "applicant-lock")
                    }
                    if ((actor && actorMissing) || (!actor && state == "missing")) {
                        return ArrayList()
                    }
                    `when`(rs.getLong("user_id")).thenReturn(if (actor) (args[0] as Number).toLong() else 8L)
                    `when`(rs.getString("name")).thenReturn("신청자")
                    `when`(rs.getString("email")).thenReturn("fixture@example.invalid")
                    `when`(rs.getString("role")).thenReturn(
                        if (actor) {
                            if ((args[0] as Number).toLong() == 1L) "ROLE_ADMIN" else actorRole
                        } else {
                            if (state == "role") "ROLE_SHELTER" else "ROLE_USER"
                        },
                    )
                } else if (sql.contains("requested_date")) {
                    events.add("daily")
                    val result = ArrayList<T>()
                    for (day in 19..20) {
                        `when`(rs.getObject("requested_date", LocalDate::class.java)).thenReturn(LocalDate.of(2026, 10, day))
                        `when`(rs.getLong("requested_count")).thenReturn(if (day == 19) 2L else 3L)
                        result.add(rowMapper.mapRow(rs, result.size)!!)
                    }
                    return result
                } else if (sql.contains("review_count")) {
                    events.add("reviews")
                    val result = ArrayList<T>()
                    for (status in listOf("APPROVED", "REJECTED")) {
                        `when`(rs.getString("status")).thenReturn(status)
                        `when`(rs.getLong("review_count")).thenReturn(if (status == "APPROVED") 4L else 5L)
                        result.add(rowMapper.mapRow(rs, result.size)!!)
                    }
                    return result
                } else if (sql.startsWith("SELECT id FROM")) {
                    events.add("latest-approval")
                    `when`(rs.getLong("id")).thenReturn(91L)
                } else {
                    val lock = sql.contains("FOR UPDATE")
                    events.add(if (sql.contains("LIMIT")) "application-page" else if (lock) "application-lock" else "application")
                    if (lock && state == "vanished") {
                        return ArrayList()
                    }
                    `when`(rs.getLong("id")).thenReturn(91L)
                    `when`(rs.getLong("user_id")).thenReturn(8L)
                    `when`(rs.getString("shelter_name")).thenReturn("보호소")
                    `when`(rs.getString("status")).thenReturn(if (lock && state == "processed") "REJECTED" else "PENDING")
                    `when`(rs.getTimestamp(eq("requested_at"), any(Calendar::class.java))).thenReturn(Timestamp.valueOf(NOW))
                }
                return arrayListOf(rowMapper.mapRow(rs, 0)!!)
            } catch (e: SQLException) {
                throw IllegalStateException(e)
            }
        }

        override fun <T> queryForObject(sql: String, requiredType: Class<T>, vararg args: Any?): T? {
            if (sql.startsWith("INSERT")) {
                events.add("insert")
                insertArgs = arrayOf(args[0], args[1], args[2])
                return requiredType.cast(91L)
            }
            events.add("count")
            return requiredType.cast(pending)
        }

        override fun update(sql: String, vararg args: Any?): Int {
            writes.add(sql)
            val user = sql.startsWith("UPDATE pawbridge_user.users")
            events.add(if (user) "user-update" else "application-update")
            if (user && failure != null) {
                throw failure!!
            }
            return 1
        }
    }

    private class Manager(private val events: MutableList<String>) : AbstractPlatformTransactionManager() {
        var failure: RuntimeException? = null
        var readOnly = false
        var isolation = 0

        override fun doGetTransaction(): Any {
            return Any()
        }

        override fun doBegin(tx: Any, definition: TransactionDefinition) {
            readOnly = definition.isReadOnly
            isolation = definition.isolationLevel
            events.add("begin")
        }

        override fun doCommit(status: DefaultTransactionStatus) {
            events.add("commit")
            if (failure != null) {
                throw failure!!
            }
        }

        override fun doRollback(status: DefaultTransactionStatus) {
            events.add("rollback")
        }

        override fun doCleanupAfterCompletion(tx: Any) {
            events.add("cleanup")
        }
    }

    companion object {
        private val NOW: LocalDateTime = LocalDateTime.parse("2026-10-20T01:02:03.123456")
    }
}
