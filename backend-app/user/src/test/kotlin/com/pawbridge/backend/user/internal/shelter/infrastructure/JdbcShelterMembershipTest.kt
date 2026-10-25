package com.pawbridge.backend.user.internal.shelter.infrastructure

import com.pawbridge.backend.user.internal.shelter.application.ShelterMembership
import com.pawbridge.backend.user.internal.shelter.application.ShelterMembership.Daily
import com.pawbridge.backend.user.internal.shelter.application.ShelterMembership.MissingShelter
import com.pawbridge.backend.user.internal.shelter.application.ShelterMembership.Shelter
import com.pawbridge.backend.user.internal.shelter.application.ShelterMembership.Status
import com.pawbridge.backend.user.internal.shelter.application.ShelterMembershipRejectedException
import io.mockk.justRun
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
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
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy
import java.sql.PreparedStatement
import java.sql.ResultSet
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
    fun `현재 역할이 주어졌을 때__신청을 제출하면__행 잠금 뒤에 역할과 대기 건수와 문구를 확인해 일반 회원만 삽입한다`(role: String) {
        // given
        val f = Fixture()
        f.jdbc.actorRole = role

        if (role == "ROLE_USER") {
            // when
            val result = f.service.submit(7, "  보호소  ")

            // then
            assertEquals("보호소", result.shelterName)
            assertEquals(Status.PENDING, result.status)
            assertEquals(91L, result.id)
            assertEquals(listOf("begin", "actor-lock", "count", "now", "insert", "commit", "cleanup"), f.events)
            assertEquals(1, f.nowCalls)
        } else {
            // when
            assertReason("FORBIDDEN") { f.service.submit(7, " ") }

            // then
            assertEquals(listOf("begin", "actor-lock", "rollback", "cleanup"), f.events)
        }
    }

    @Test
    fun `대기 중인 신청이 있을 때__잘못된 문구로 제출하면__문구 검증보다 먼저 충돌로 거부한다`() {
        // given
        val f = Fixture()
        f.jdbc.pending = 1

        // when
        assertReason("CONFLICT") { f.service.submit(7, " ") }

        // then
        assertEquals(0, f.nowCalls)
        assertFalse(f.events.contains("insert"))
    }

    @Test
    fun `대기 중인 신청자일 때__승인하면__신청 갱신 전에 회원을 잠그고 같은 트랜잭션 안에서 동물 모듈을 호출한다`() {
        // given
        val f = Fixture()

        // when
        val result = f.service.approve(1, 91, " reg ", " 승인 사유 ")

        // then
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
    fun `갱신된 상태가 처리됨이거나 역할이 달라졌거나 신청자가 없을 때__승인하면__동물 호출과 모든 쓰기 전에 멈춘다`(state: String) {
        // given
        val f = Fixture()
        f.jdbc.state = state

        // when
        if (state == "vanished") {
            assertThrows(OptimisticLockingFailureException::class.java) { f.service.approve(1, 91, "reg", "note") }
        } else {
            assertReason(if (state == "missing") "NOT_FOUND" else "CONFLICT") { f.service.approve(1, 91, "reg", "note") }
        }

        // then
        assertFalse(f.events.contains("animal"))
        assertTrue(f.jdbc.writes.isEmpty())
        assertTrue(f.events.contains("rollback"))
    }

    @ParameterizedTest
    @ValueSource(strings = ["404", "runtime", "null", "wrong"])
    fun `동물 모듈이 실패하거나 신원이 잘못됐을 때__승인하면__쓰기 없이 원래 비즈니스 실패만 돌려준다`(
        phase: String,
    ) {
        // given
        val f = Fixture()
        f.animal = phase

        // when
        assertReason(if (phase == "404") "SHELTER_NOT_FOUND" else "SHELTER_UNAVAILABLE") {
            f.service.approve(1, 91, "reg", "note")
        }

        // then
        assertTrue(f.jdbc.writes.isEmpty())
        assertEquals(0, f.nowCalls)
    }

    @Test
    fun `대기 중인 신청일 때__반려하면__잠금 뒤에 사유를 검증하고 사용자 역할은 건드리지 않는다`() {
        // given
        val invalid = Fixture()
        val f = Fixture()

        // when
        assertReason("INVALID") { invalid.service.reject(1, 91, " ") }
        val result = f.service.reject(1, 91, " 이유 ")

        // then
        assertTrue(invalid.events.contains("application-lock"))
        assertTrue(invalid.jdbc.writes.isEmpty())
        assertEquals("이유", result.application!!.reason)
        assertEquals(Status.REJECTED, result.application!!.status)
        assertEquals(1, f.jdbc.writes.size)
        assertFalse(f.events.contains("animal"))
        assertFalse(f.events.contains("user-update"))
    }

    @Test
    fun `데이터베이스의 관리자 역할이 바뀌었을 때__관리자 작업을 호출하면__대상이나 입력 조회 전에 거부한다`() {
        // given
        val f = Fixture()
        f.jdbc.actorRole = "ROLE_USER"

        // when
        assertReason("FORBIDDEN") { f.service.approve(7, 91, " ", " ") }

        // then
        assertEquals(listOf("begin", "actor", "rollback", "cleanup"), f.events)
    }

    @Test
    fun `어떤 기존 역할이든 본인 페이지일 때__목록을 조회하면__페이지 검증 전에 인증하고 관리자 요구 없이 매핑한다`() {
        // given
        val missing = Fixture()
        missing.jdbc.actorMissing = true

        // when
        for (role in listOf("ROLE_USER", "ROLE_ADMIN", "ROLE_SHELTER")) {
            val f = Fixture()
            f.jdbc.actorRole = role
            val result = f.service.mine(7, 0, 20)

            // then
            assertEquals(1L, result.total)
            assertEquals(1, result.content.size)
            assertEquals(listOf("begin", "actor", "application-page", "commit", "cleanup"), f.events)
        }
        assertReason("TOKEN_INVALID") { missing.service.mine(7, -1, 0) }
    }

    @Test
    fun `관리자 페이지와 회원 목록일 때__보강하면__사용자 배치 조회는 한 번이고 회원별 최근 승인 조회를 유지한다`() {
        // given
        val f = Fixture()
        val member = Fixture()

        // when
        val applicantName = f.service.list(1, null, 0, 20).content.first().applicantName
        val result = member.service.members(1, " reg ", 0, 20)

        // then
        assertEquals("신청자", applicantName)
        assertEquals(1, Collections.frequency(f.events, "user-batch"))
        assertEquals(91L, result.content.first().approvalApplicationId)
        assertEquals(1, Collections.frequency(member.events, "latest-approval"))
    }

    @Test
    fun `서울 날짜와 듬성한 행일 때__통계를 읽으면__반복 가능 읽기를 쓰고 전날 건수와 현재 대기와 쿼리 순서를 유지한다`() {
        // given
        val f = Fixture()
        val accepted = Fixture()
        val day = LocalDate.parse("2026-10-20")

        // when
        val result = f.service.statistics(1, day, day)
        accepted.service.statistics(1, day.minusDays(365), day)

        // then
        assertEquals(2L, result.previousDayCount)
        assertEquals(listOf(Daily(day, 3)), result.daily)
        assertEquals(4L, result.approvedCount)
        assertEquals(5L, result.rejectedCount)
        assertEquals(listOf("begin", "actor", "daily", "reviews", "count", "commit", "cleanup"), f.events)
        assertTrue(f.manager.readOnly)
        assertEquals(TransactionDefinition.ISOLATION_REPEATABLE_READ, f.manager.isolation)
    }

    @ParameterizedTest
    @ValueSource(strings = ["366", "future", "reverse", "null"])
    fun `잘못된 통계 날짜일 때__조회하면__현재 관리자 확인 뒤 집계 SQL 전에 거부한다`(mode: String) {
        // given
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

        // when
        assertReason("INVALID_INPUT") { f.service.statistics(1, from, to) }

        // then
        assertEquals(listOf("begin", "actor", "rollback", "cleanup"), f.events)
    }

    @ParameterizedTest
    @ValueSource(strings = ["second-write", "commit"])
    fun `뒤늦은 쓰기나 커밋이 실패할 때__승인하면__부분 응답이나 재시도 없이 그대로 전파한다`(phase: String) {
        // given
        val f = Fixture()
        val failure: RuntimeException = TransactionSystemException("synthetic-private")
        if (phase == "commit") {
            f.manager.failure = failure
        } else {
            f.jdbc.failure = failure
        }

        // when
        val thrown = assertThrows(RuntimeException::class.java) { f.service.approve(1, 91, "reg", "note") }

        // then
        assertSame(failure, thrown)
        assertEquals(1, Collections.frequency(f.events, "begin"))
        assertEquals("cleanup", f.events.last())
    }

    @Test
    fun `로컬 마이크로초 시각일 때__타임스탬프를 바인딩하면__불필요한 사용자 컬럼을 읽지 않고 UTC 캘린더를 쓴다`() {
        // given
        val f = Fixture()
        f.service.submit(7, "이름")
        val timestamp = f.jdbc.insertArgs!![2] as SqlValue
        val statement = mockk<PreparedStatement>()

        justRun { statement.setTimestamp(any(), any<Timestamp>(), any<Calendar>()) }

        // when
        timestamp.setValue(statement, 1)

        // then
        val calendar = slot<Calendar>()
        verify(exactly = 1) { statement.setTimestamp(1, Timestamp.valueOf(NOW), capture(calendar)) }
        assertEquals("UTC", calendar.captured.timeZone.id)
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
                val role = if (actor) {
                    if ((args[0] as Number).toLong() == 1L) "ROLE_ADMIN" else actorRole
                } else {
                    if (state == "role") "ROLE_SHELTER" else "ROLE_USER"
                }
                val user = row(
                    "user_id" to (if (actor) (args[0] as Number).toLong() else 8L),
                    "name" to "신청자",
                    "email" to "fixture@example.invalid",
                    "role" to role,
                )
                return arrayListOf(rowMapper.mapRow(user, 0)!!)
            }
            if (sql.contains("requested_date")) {
                events.add("daily")
                val result = ArrayList<T>()
                for (day in 19..20) {
                    val daily = row(
                        "requested_date" to LocalDate.of(2026, 10, day),
                        "requested_count" to (if (day == 19) 2L else 3L),
                    )
                    result.add(rowMapper.mapRow(daily, result.size)!!)
                }
                return result
            }
            if (sql.contains("review_count")) {
                events.add("reviews")
                val result = ArrayList<T>()
                for (status in listOf("APPROVED", "REJECTED")) {
                    val review = row(
                        "status" to status,
                        "review_count" to (if (status == "APPROVED") 4L else 5L),
                    )
                    result.add(rowMapper.mapRow(review, result.size)!!)
                }
                return result
            }
            if (sql.startsWith("SELECT id FROM")) {
                events.add("latest-approval")
                return arrayListOf(rowMapper.mapRow(row("id" to 91L), 0)!!)
            }
            val lock = sql.contains("FOR UPDATE")
            events.add(if (sql.contains("LIMIT")) "application-page" else if (lock) "application-lock" else "application")
            if (lock && state == "vanished") {
                return ArrayList()
            }
            val application = row(
                "id" to 91L,
                "user_id" to 8L,
                "shelter_name" to "보호소",
                "status" to (if (lock && state == "processed") "REJECTED" else "PENDING"),
                "requested_at" to Timestamp.valueOf(NOW),
            )
            return arrayListOf(rowMapper.mapRow(application, 0)!!)
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
        /**
         * In-memory ResultSet answering typed getters by column label; unknown columns read as null, 0 or false
         * like the permissive row the first-stage test used.
         */
        private fun row(vararg cells: Pair<String, Any?>): ResultSet {
            val values = mapOf(*cells)
            val handler = InvocationHandler { _, method, args ->
                val value = if (method.name.startsWith("get") && args != null && args[0] is String) {
                    values[args[0] as String]
                } else {
                    null
                }
                when {
                    method.returnType == java.lang.Long.TYPE -> (value as Number?)?.toLong() ?: 0L
                    method.returnType == Integer.TYPE -> (value as Number?)?.toInt() ?: 0
                    method.returnType == java.lang.Boolean.TYPE -> (value as Boolean?) ?: false
                    else -> value
                }
            }
            return Proxy.newProxyInstance(
                ResultSet::class.java.classLoader,
                arrayOf<Class<*>>(ResultSet::class.java),
                handler,
            ) as ResultSet
        }

        private val NOW: LocalDateTime = LocalDateTime.parse("2026-10-20T01:02:03.123456")
    }
}
