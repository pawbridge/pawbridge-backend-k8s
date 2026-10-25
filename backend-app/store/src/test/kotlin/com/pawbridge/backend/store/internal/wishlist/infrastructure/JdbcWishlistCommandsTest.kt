package com.pawbridge.backend.store.internal.wishlist.infrastructure

import com.pawbridge.backend.store.internal.wishlist.application.WishlistCommands
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.function.Executable
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyMap
import org.mockito.ArgumentMatchers.anyString
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mockito.RETURNS_DEFAULTS
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.mock
import org.mockito.Mockito.times
import org.mockito.Mockito.`when`
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.verifyNoMoreInteractions
import org.mockito.stubbing.Answer
import org.springframework.dao.DataAccessResourceFailureException
import org.springframework.jdbc.core.ResultSetExtractor
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcOperations
import org.springframework.transaction.TransactionStatus
import org.springframework.transaction.support.SimpleTransactionStatus
import org.springframework.transaction.support.TransactionCallback
import org.springframework.transaction.support.TransactionOperations
import java.sql.ResultSet
import java.time.Clock
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicInteger
import java.util.function.Consumer

class JdbcWishlistCommandsTest {
    private val jdbc: NamedParameterJdbcOperations = mock(NamedParameterJdbcOperations::class.java)
    private val write: TransactionOperations = mock(TransactionOperations::class.java)
    private val clock: Clock = mock(Clock::class.java)
    private val commands = JdbcWishlistCommands(jdbc, write, clock)
    private var active = false
    private var duplicate = false
    private var identity: Long? = 9007199254740993L
    private var matches: List<Long> = listOf(33L)
    private var deleted = 1
    private var sku: ResultSet? = null
    private val events = ArrayList<String>()
    private val sql = ArrayList<String>()
    private val params = ArrayList<Map<String, *>>()
    private val instant: Instant = Instant.parse("2026-10-20T00:00:01.123456789Z")
    private val created: LocalDateTime = LocalDateTime.of(2026, 10, 20, 9, 0, 1, 123456789)

    @BeforeEach
    fun setup() {
        `when`(clock.instant()).thenReturn(instant)
        `when`(clock.zone).thenReturn(ZoneId.of("Asia/Seoul"))
        `when`(write.execute(any<TransactionCallback<Any>>())).thenAnswer { call ->
            active = true
            try {
                call.getArgument<TransactionCallback<*>>(0).doInTransaction(SimpleTransactionStatus())
            } finally {
                active = false
            }
        }
        doAnswer { call ->
            active = true
            try {
                call.getArgument<Consumer<TransactionStatus>>(0).accept(SimpleTransactionStatus())
            } finally {
                active = false
            }
            null
        }.`when`(write).executeWithoutResult(any<Consumer<TransactionStatus>>())
        `when`(jdbc.queryForObject(anyString(), anyMap<String, Any>(), eq(Boolean::class.javaObjectType))).thenAnswer { call ->
            capture("exists", call.getArgument<String>(0), call.getArgument<Map<String, *>>(1))
            duplicate
        }
        `when`(jdbc.queryForObject(anyString(), anyMap<String, Any>(), eq(Long::class.javaObjectType))).thenAnswer { call ->
            capture("insert", call.getArgument<String>(0), call.getArgument<Map<String, *>>(1))
            identity
        }
        `when`(jdbc.query(anyString(), anyMap<String, Any>(), any<ResultSetExtractor<Any>>())).thenAnswer { call ->
            capture("sku", call.getArgument<String>(0), call.getArgument<Map<String, *>>(1))
            call.getArgument<ResultSetExtractor<*>>(2).extractData(sku!!)
        }
        `when`(jdbc.query(anyString(), anyMap<String, Any>(), any<RowMapper<Any>>())).thenAnswer { call ->
            capture("find", call.getArgument<String>(0), call.getArgument<Map<String, *>>(1))
            matches
        }
        `when`(jdbc.update(anyString(), anyMap<String, Any>())).thenAnswer { call ->
            capture("delete", call.getArgument<String>(0), call.getArgument<Map<String, *>>(1))
            deleted
        }
    }

    @Test
    fun given__existing_sku_and_options__when__adding__then__preserve_precedence_generated_identity_local_audit_and_response() {
        sku = rows(
            map(
                "sku_id", 80L, "sku_code", "SKU", "product_id", 9L, "product_name", "상품", "image_url", null,
                "price", 9007199254740993L, "option_value_id", 3L, "group_name", "색상", "value_name", "파랑",
            ),
            map("option_value_id", 4L, "group_name", "크기", "value_name", "대"),
        )

        assertEquals(
            WishlistCommands.Added(
                identity, 7L, 80L, "SKU", 9L, "상품", null, 9007199254740993L,
                mutableMapOf("색상" to "파랑", "크기" to "대"), created,
            ),
            commands.add(7, 80L),
        )

        assertEquals(listOf("exists", "sku", "insert"), events)
        assertTrue(sql[0].contains("user_id=:member AND sku_id=:sku"))
        assertTrue(sql[2].contains("RETURNING id"))
        assertTrue(sql[2].contains("VALUES (:member,:sku,:created,:created)"))
        assertEquals(mapOf("member" to 7L, "sku" to 80L, "created" to created), params[2])
        assertFalse(sql[1].contains("status="))
        assertFalse(sql[1].contains("stock_quantity>"))
        verify(write, times(1)).execute(any<TransactionCallback<Any>>())
        verify(clock, times(1)).instant()
        assertFalse(active)
    }

    @Test
    fun given__sku_without_options__when__adding__then__return_empty_option_map() {
        sku = rows(
            map(
                "sku_id", 80L, "sku_code", "SKU", "product_id", 9L, "product_name", "상품", "image_url", "image",
                "price", 0L, "option_value_id", null,
            ),
        )

        val added = commands.add(7, 80L)

        assertEquals(mapOf<String, String>(), added.options)
        assertEquals(0L, added.price)
        assertEquals("image", added.productImageUrl)
    }

    @Test
    fun given__duplicate_pair__when__adding__then__preserve_original_state_error_before_sku_or_insert() {
        duplicate = true

        assertEquals(
            "이미 찜한 상품입니다.",
            assertThrows(IllegalStateException::class.java) { commands.add(7, 80L) }.message,
        )

        assertEquals(listOf("exists"), events)
        verifyNoInteractions(clock)
        assertFalse(active)
    }

    @Test
    fun given__missing_sku__when__adding__then__preserve_bad_input_and_do_not_insert() {
        sku = rows()

        assertEquals(
            "SKU를 찾을 수 없습니다: 80",
            assertThrows(IllegalArgumentException::class.java) { commands.add(7, 80L) }.message,
        )

        assertEquals(listOf("exists", "sku"), events)
        verifyNoInteractions(clock)
    }

    @Test
    fun given__null_sku__when__adding__then__keep_duplicate_first_and_fail_as_bad_input_without_sku_query() {
        assertThrows(IllegalArgumentException::class.java) { commands.add(7, null) }

        assertEquals(listOf("exists"), events)
        assertNull(params.first()["sku"])
        verifyNoInteractions(clock)
    }

    @Test
    fun given__insert_failure__when__adding__then__propagate_original_failure_without_success_result() {
        sku = rows(map("sku_id", 80L, "product_id", 9L, "price", 1L))
        val failure = DataAccessResourceFailureException("synthetic SQL detail")
        doThrow(failure).`when`(jdbc).queryForObject(anyString(), anyMap<String, Any>(), eq(Long::class.javaObjectType))

        assertSame(failure, assertThrows(DataAccessResourceFailureException::class.java) { commands.add(7, 80L) })

        assertFalse(active)
    }

    @Test
    fun given__missing_generated_identity__when__adding__then__fail_instead_of_fabricating_an_id() {
        identity = null
        sku = rows(map("sku_id", 80L, "product_id", 9L, "price", 1L))

        assertThrows(IllegalStateException::class.java) { commands.add(7, 80L) }

        assertFalse(active)
    }

    @Test
    fun given__one_owned_pair__when__removing_by_sku__then__delete_only_selected_identity_member_and_sku() {
        commands.removeByMemberAndSku(7, 80)

        assertEquals(listOf("find", "delete"), events)
        assertTrue(sql.first().contains("user_id=:member AND sku_id=:sku"))
        assertEquals("DELETE FROM pawbridge_store.wishlists WHERE id=:id AND user_id=:member AND sku_id=:sku", sql[1])
        assertEquals(mapOf("member" to 7L, "sku" to 80L, "id" to 33L), params[1])
        assertFalse(active)
    }

    @Test
    fun given__pair_not_found__when__removing_by_sku__then__same_bad_input_and_no_delete() {
        matches = listOf()

        assertEquals(
            "찜을 찾을 수 없습니다.",
            assertThrows(IllegalArgumentException::class.java) { commands.removeByMemberAndSku(7, 80) }.message,
        )

        assertEquals(listOf("find"), events)
    }

    @Test
    fun given__preexisting_duplicate_pair__when__removing_by_sku__then__fail_without_deleting_one_or_all() {
        matches = listOf(33L, 34L)

        assertThrows(IllegalStateException::class.java) { commands.removeByMemberAndSku(7, 80) }

        assertEquals(listOf("find"), events)
    }

    @Test
    fun given__owned_identity__when__removing_by_id__then__one_atomic_owner_scoped_delete_without_lookup() {
        commands.removeById(7, 33)

        assertEquals(listOf("delete"), events)
        assertEquals("DELETE FROM pawbridge_store.wishlists WHERE id=:id AND user_id=:member", sql.first())
        assertEquals(mapOf("member" to 7L, "id" to 33L), params.first())
        verify(write, times(1)).executeWithoutResult(any<Consumer<TransactionStatus>>())
        verify(jdbc).update(sql.first(), params.first())
        verifyNoMoreInteractions(jdbc)
        verifyNoInteractions(clock)
        assertFalse(active)
    }

    @Test
    fun given__wrong_owner_or_missing_identity__when__removing_by_id__then__zero_rows_is_the_same_noop_without_lookup() {
        deleted = 0

        assertDoesNotThrow(Executable { commands.removeById(8, 33) })

        assertEquals(listOf("delete"), events)
        assertEquals(mapOf("member" to 8L, "id" to 33L), params.first())
        assertEquals("DELETE FROM pawbridge_store.wishlists WHERE id=:id AND user_id=:member", sql.first())
        verify(jdbc).update(sql.first(), params.first())
        verifyNoMoreInteractions(jdbc)
        verifyNoInteractions(clock)
        assertFalse(active)
    }

    @Test
    fun given__identity_already_removed__when__retrying_or_losing_a_delete_race__then__zero_rows_stays_a_noop() {
        commands.removeById(7, 33)
        deleted = 0

        assertDoesNotThrow(Executable { commands.removeById(7, 33) })

        assertEquals(listOf("delete", "delete"), events)
        assertEquals(listOf(mapOf("member" to 7L, "id" to 33L), mapOf("member" to 7L, "id" to 33L)), params)
        verify(write, times(2)).executeWithoutResult(any<Consumer<TransactionStatus>>())
        verify(jdbc, times(2)).update(sql.first(), params.first())
        verifyNoMoreInteractions(jdbc)
        verifyNoInteractions(clock)
        assertFalse(active)
    }

    @Test
    fun given__concurrent_disappearance__when__deleting_selected_pair__then__fail_instead_of_claiming_delete_success() {
        deleted = 0

        assertThrows(IllegalStateException::class.java) { commands.removeByMemberAndSku(7, 80) }

        assertFalse(active)
    }

    @Test
    fun given__database_failure__when__deleting_identity__then__propagate_without_lookup_or_retry() {
        val failure = DataAccessResourceFailureException("synthetic SQL")
        doThrow(failure).`when`(jdbc).update(anyString(), anyMap<String, Any>())

        assertSame(failure, assertThrows(DataAccessResourceFailureException::class.java) { commands.removeById(7, 33) })

        verify(jdbc).update(
            "DELETE FROM pawbridge_store.wishlists WHERE id=:id AND user_id=:member",
            mapOf("member" to 7L, "id" to 33L),
        )
        verifyNoMoreInteractions(jdbc)
        assertFalse(active)
    }

    private fun capture(event: String, query: String, values: Map<String, *>) {
        assertTrue(active)
        events.add(event)
        sql.add(query)
        params.add(HashMap<String, Any?>(values))
    }

    companion object {
        private fun map(vararg values: Any?): MutableMap<String, Any?> {
            val result = HashMap<String, Any?>()
            var i = 0
            while (i < values.size) {
                result[values[i] as String] = values[i + 1]
                i += 2
            }
            return result
        }

        private fun rows(vararg values: Map<String, Any?>): ResultSet {
            val i = AtomicInteger(-1)
            return mock(
                ResultSet::class.java,
                Answer<Any?> { call ->
                    val method = call.method.name
                    if (method == "next") {
                        return@Answer i.incrementAndGet() < values.size
                    }
                    if (method.startsWith("get") && call.getArgument<Any?>(0) is String) {
                        val column = call.getArgument<String>(0)
                        val value = values[i.get()][column]
                        return@Answer if (method == "getLong") {
                            if (value == null) 0L else (value as Number).toLong()
                        } else {
                            value
                        }
                    }
                    RETURNS_DEFAULTS.answer(call)
                },
            )
        }
    }
}
