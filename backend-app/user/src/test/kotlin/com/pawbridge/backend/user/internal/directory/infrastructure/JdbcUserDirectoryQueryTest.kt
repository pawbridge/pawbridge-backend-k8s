package com.pawbridge.backend.user.internal.directory.infrastructure

import com.pawbridge.backend.user.internal.directory.application.UserDirectoryQuery
import com.pawbridge.backend.user.internal.profile.application.UserProfile
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.springframework.dao.DataAccessResourceFailureException
import org.springframework.dao.InvalidDataAccessApiUsageException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.TransactionSystemException
import org.springframework.transaction.support.AbstractPlatformTransactionManager
import org.springframework.transaction.support.DefaultTransactionStatus
import org.springframework.transaction.support.TransactionTemplate
import java.sql.ResultSet
import java.sql.SQLException
import java.sql.Timestamp
import java.time.LocalDateTime
import java.util.Calendar

class JdbcUserDirectoryQueryTest {
    @Test
    fun given__a_raw_nullable_profile__when__listing__then__map_only_the_existing_seven_columns_in_the_read_transaction() {
        val f = Fixture()
        val timed = UserProfile(7, null, null, "raw nickname", "LOCAL", "ROLE_ADMIN", LocalDateTime.parse("2026-10-20T01:02:03.123456"))
        f.jdbc.rows = listOf(ROW, timed)

        val result = f.query.page(null, null, request(0, 20))

        assertEquals(listOf(ROW, timed), result.content())
        assertEquals(2L, result.total())
        assertEquals(listOf("begin", "content", "commit", "cleanup"), f.events)
        assertEquals(
            "SELECT user_id, email, name, nickname, provider, role, created_at FROM pawbridge_user.users ORDER BY created_at DESC LIMIT ? OFFSET ?",
            f.jdbc.sql.first(),
        )
        assertArrayEquals(arrayOf<Any?>(20, 0L), f.jdbc.arguments.first())
        assertEquals(listOf("UTC", "UTC"), f.jdbc.zones)
        assertTrue(f.manager.readOnly)
        assertEquals(3, f.manager.timeout)
    }

    @Test
    fun given__raw_wildcards_spaces_and_role__when__searching__then__retain_three_like_operands_and_role_and_without_escaping_or_stripping() {
        val f = Fixture()

        f.query.page(" %_\\한글 ", "ROLE_SHELTER", request(0, 1))

        assertTrue(
            f.jdbc.sql.first().contains(
                "lower(email) LIKE lower(?) ESCAPE '' OR lower(name) LIKE lower(?) ESCAPE '' OR lower(nickname) LIKE lower(?) ESCAPE ''",
            ),
        )
        assertTrue(f.jdbc.sql.first().contains(") AND role = ? ORDER BY"))
        assertArrayEquals(
            arrayOf<Any?>("% %_\\한글 %", "% %_\\한글 %", "% %_\\한글 %", "ROLE_SHELTER", 1, 0L),
            f.jdbc.arguments.first(),
        )
        assertArrayEquals(
            arrayOf<Any?>("% %_\\한글 %", "% %_\\한글 %", "% %_\\한글 %", "ROLE_SHELTER"),
            f.jdbc.arguments[1],
        )
        val empty = Fixture()
        empty.query.page("", null, request(0, 20))
        assertFalse(empty.jdbc.sql.first().contains(" WHERE "))
    }

    @ParameterizedTest
    @CsvSource("0,20,0,false,0", "0,20,1,false,1", "2,20,1,false,41", "2,20,0,true,49", "0,1,1,true,49")
    fun given__full_partial_or_empty_pages__when__reading__then__match_original_count_elision(
        number: Int,
        size: Int,
        rows: Int,
        counted: Boolean,
        total: Long,
    ) {
        val f = Fixture()
        if (rows == 0) {
            f.jdbc.rows = listOf()
        }

        val result = f.query.page(null, null, request(number, size))

        assertEquals(total, result.total())
        assertEquals(if (counted) 2 else 1, f.jdbc.sql.size)
        assertEquals(
            if (counted) {
                listOf("begin", "content", "count", "commit", "cleanup")
            } else {
                listOf("begin", "content", "commit", "cleanup")
            },
            f.events,
        )
    }

    @Test
    fun given__the_seven_projected_properties__when__sorting__then__use_fixed_identifiers_and_lower_only_source_string_types_without_an_added_tiebreak() {
        val f = Fixture()
        val orders = listOf(
            UserDirectoryQuery.Order("UserId", true, true),
            UserDirectoryQuery.Order("email", false, true),
            UserDirectoryQuery.Order("name", true, true),
            UserDirectoryQuery.Order("nickname", true, true),
            UserDirectoryQuery.Order("provider", true, true),
            UserDirectoryQuery.Order("role", true, true),
            UserDirectoryQuery.Order("createdAt", true, true),
        )

        f.query.page(null, null, UserDirectoryQuery.Request(0, 20, orders))

        assertTrue(
            f.jdbc.sql.first().endsWith(
                "ORDER BY user_id ASC, lower(email) DESC, lower(name) ASC, lower(nickname) ASC, lower(provider) ASC, role ASC, created_at ASC LIMIT ? OFFSET ?",
            ),
        )
    }

    @Test
    fun given__unprojected_source_fields_or_unmapped_identifiers__when__sorting__then__reject_in_the_transaction_before_any_sql() {
        // The first four are a known, deliberately narrower candidate capability than legacy User JPA sorting.
        for (
            property in listOf(
                "password", "careRegNo", "providerId", "updatedAt", "email DESC; DELETE FROM users", "created_at", "class",
            )
        ) {
            val f = Fixture()
            assertThrows(IllegalArgumentException::class.java) {
                f.query.page(
                    null,
                    null,
                    UserDirectoryQuery.Request(0, 20, listOf(UserDirectoryQuery.Order(property, true, false))),
                )
            }
            assertTrue(f.jdbc.sql.isEmpty())
            assertEquals(listOf("begin", "rollback", "cleanup"), f.events)
        }
        val f = Fixture()
        assertThrows(InvalidDataAccessApiUsageException::class.java) {
            f.query.page(null, null, request(Int.MAX_VALUE, 20))
        }
        assertTrue(f.jdbc.sql.isEmpty())
    }

    @ParameterizedTest
    @ValueSource(strings = ["content", "count", "commit"])
    fun given__content_count_or_commit_failure__when__reading__then__propagate_without_a_partial_page_or_retry(phase: String) {
        val f = Fixture()
        val failure: RuntimeException = if (phase == "commit") {
            TransactionSystemException("synthetic-private")
        } else {
            DataAccessResourceFailureException("synthetic-private")
        }
        if (phase == "commit") {
            f.manager.failure = failure
        } else {
            f.jdbc.failurePhase = phase
        }
        f.jdbc.failure = failure

        assertSame(failure, assertThrows(RuntimeException::class.java) { f.query.page(null, null, request(0, 1)) })

        assertEquals(1L, f.events.count { event -> "begin" == event }.toLong())
        assertEquals("cleanup", f.events.last())
        assertEquals(if (phase == "commit") "commit" else "rollback", f.events[f.events.size - 2])
    }

    private class Fixture {
        val events = ArrayList<String>()
        val jdbc = RecordingJdbc(events)
        val manager = Manager(events)
        val query: JdbcUserDirectoryQuery

        init {
            val tx = TransactionTemplate(manager)
            tx.isReadOnly = true
            tx.timeout = 3
            query = JdbcUserDirectoryQuery(jdbc, tx)
        }
    }

    private class RecordingJdbc(private val events: MutableList<String>) : JdbcTemplate() {
        val sql = ArrayList<String>()
        val arguments = ArrayList<Array<out Any?>>()
        val zones = ArrayList<String>()
        var rows: List<UserProfile> = listOf(ROW)
        var failurePhase: String? = null
        var failure: RuntimeException? = null

        override fun <T> query(query: String, rowMapper: RowMapper<T>, vararg args: Any?): MutableList<T> {
            events.add("content")
            sql.add(query)
            arguments.add(args)
            if ("content" == failurePhase) {
                throw failure!!
            }
            val mapped = ArrayList<T>()
            try {
                for (user in rows) {
                    val row = mock(ResultSet::class.java)
                    `when`(row.getLong("user_id")).thenReturn(user.userId)
                    `when`(row.getString("email")).thenReturn(user.email)
                    `when`(row.getString("name")).thenReturn(user.name)
                    `when`(row.getString("nickname")).thenReturn(user.nickname)
                    `when`(row.getString("provider")).thenReturn(user.provider)
                    `when`(row.getString("role")).thenReturn(user.role)
                    `when`(row.getTimestamp(eq("created_at"), any(Calendar::class.java))).thenAnswer { call ->
                        zones.add(call.getArgument<Calendar>(1).timeZone.id)
                        if (user.createdAt == null) null else Timestamp.valueOf(user.createdAt)
                    }
                    mapped.add(rowMapper.mapRow(row, mapped.size)!!)
                }
                return mapped
            } catch (error: SQLException) {
                throw IllegalStateException(error)
            }
        }

        override fun <T> queryForObject(query: String, requiredType: Class<T>, vararg args: Any?): T? {
            events.add("count")
            sql.add(query)
            arguments.add(args)
            if ("count" == failurePhase) {
                throw failure!!
            }
            return requiredType.cast(49L)
        }
    }

    private class Manager(private val events: MutableList<String>) : AbstractPlatformTransactionManager() {
        var failure: RuntimeException? = null
        var readOnly = false
        var timeout = 0

        override fun doGetTransaction(): Any {
            return Any()
        }

        override fun doBegin(transaction: Any, definition: TransactionDefinition) {
            readOnly = definition.isReadOnly
            timeout = definition.timeout
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

        override fun doCleanupAfterCompletion(transaction: Any) {
            events.add("cleanup")
        }
    }

    companion object {
        private val ROW = UserProfile(Long.MAX_VALUE, "raw@example.invalid", " 한글 ", null, "GOOGLE", "ROLE_USER", null)

        private fun request(number: Int, size: Int): UserDirectoryQuery.Request {
            return UserDirectoryQuery.Request(number, size, listOf(UserDirectoryQuery.Order("createdAt", false, false)))
        }
    }
}
