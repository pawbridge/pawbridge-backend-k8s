package com.pawbridge.backend.store.internal.wishlist.infrastructure

import com.pawbridge.backend.store.internal.wishlist.application.WishlistCommands
import io.mockk.confirmVerified
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.function.Executable
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
    private val jdbc: NamedParameterJdbcOperations = mockk<NamedParameterJdbcOperations>()
    private val write: TransactionOperations = mockk<TransactionOperations>()
    private val clock: Clock = mockk<Clock>()
    private val commands = JdbcWishlistCommands(jdbc, write, clock)
    private var active = false
    private val events = ArrayList<String>()
    private val sql = ArrayList<String>()
    private val params = ArrayList<Map<String, *>>()
    private val instant: Instant = Instant.parse("2026-10-20T00:00:01.123456789Z")
    private val created: LocalDateTime = LocalDateTime.of(2026, 10, 20, 9, 0, 1, 123456789)

    @Test
    fun `기존 SKU와 옵션이 있을 때__찜을 추가하면__우선순위와 생성된 ID와 로컬 감사 시각과 응답을 보존한다`() {
        // given
        val identity = 9007199254740993L
        val sku = rows(
            map(
                "sku_id", 80L, "sku_code", "SKU", "product_id", 9L, "product_name", "상품", "image_url", null,
                "price", 9007199254740993L, "option_value_id", 3L, "group_name", "색상", "value_name", "파랑",
            ),
            map("option_value_id", 4L, "group_name", "크기", "value_name", "대"),
        )

        every { clock.instant() } returns instant
        every { clock.zone } returns ZoneId.of("Asia/Seoul")
        every { write.execute(any<TransactionCallback<Any>>()) } answers {
            inTransaction { firstArg<TransactionCallback<*>>().doInTransaction(SimpleTransactionStatus()) }
        }
        every { jdbc.queryForObject(any<String>(), any<Map<String, *>>(), Boolean::class.javaObjectType) } answers {
            capture("exists", firstArg(), secondArg())
            false
        }
        every { jdbc.query(any<String>(), any<Map<String, *>>(), any<ResultSetExtractor<Any>>()) } answers {
            capture("sku", firstArg(), secondArg())
            thirdArg<ResultSetExtractor<*>>().extractData(sku)
        }
        every { jdbc.queryForObject(any<String>(), any<Map<String, *>>(), Long::class.javaObjectType) } answers {
            capture("insert", firstArg(), secondArg())
            identity
        }

        // when
        val added = commands.add(7, 80L)

        // then
        assertEquals(
            WishlistCommands.Added(
                identity, 7L, 80L, "SKU", 9L, "상품", null, 9007199254740993L,
                mutableMapOf("색상" to "파랑", "크기" to "대"), created,
            ),
            added,
        )
        assertEquals(listOf("exists", "sku", "insert"), events)
        assertTrue(sql[0].contains("user_id=:member AND sku_id=:sku"))
        assertTrue(sql[2].contains("RETURNING id"))
        assertTrue(sql[2].contains("VALUES (:member,:sku,:created,:created)"))
        assertEquals(mapOf("member" to 7L, "sku" to 80L, "created" to created), params[2])
        assertFalse(sql[1].contains("status="))
        assertFalse(sql[1].contains("stock_quantity>"))
        verify(exactly = 1) { write.execute(any<TransactionCallback<Any>>()) }
        verify(exactly = 1) { clock.instant() }
        assertFalse(active)
    }

    @Test
    fun `SKU에 옵션이 없을 때__찜을 추가하면__빈 옵션 맵을 돌려준다`() {
        // given
        val sku = rows(
            map(
                "sku_id", 80L, "sku_code", "SKU", "product_id", 9L, "product_name", "상품", "image_url", "image",
                "price", 0L, "option_value_id", null,
            ),
        )

        every { clock.instant() } returns instant
        every { clock.zone } returns ZoneId.of("Asia/Seoul")
        every { write.execute(any<TransactionCallback<Any>>()) } answers {
            inTransaction { firstArg<TransactionCallback<*>>().doInTransaction(SimpleTransactionStatus()) }
        }
        every { jdbc.queryForObject(any<String>(), any<Map<String, *>>(), Boolean::class.javaObjectType) } answers {
            capture("exists", firstArg(), secondArg())
            false
        }
        every { jdbc.query(any<String>(), any<Map<String, *>>(), any<ResultSetExtractor<Any>>()) } answers {
            capture("sku", firstArg(), secondArg())
            thirdArg<ResultSetExtractor<*>>().extractData(sku)
        }
        every { jdbc.queryForObject(any<String>(), any<Map<String, *>>(), Long::class.javaObjectType) } answers {
            capture("insert", firstArg(), secondArg())
            9007199254740993L
        }

        // when
        val added = commands.add(7, 80L)

        // then
        assertEquals(mapOf<String, String>(), added.options)
        assertEquals(0L, added.price)
        assertEquals("image", added.productImageUrl)
    }

    @Test
    fun `이미 찜한 쌍이 있을 때__찜을 추가하면__SKU 조회나 삽입 전에 원래의 상태 오류를 유지한다`() {
        // given
        every { write.execute(any<TransactionCallback<Any>>()) } answers {
            inTransaction { firstArg<TransactionCallback<*>>().doInTransaction(SimpleTransactionStatus()) }
        }
        every { jdbc.queryForObject(any<String>(), any<Map<String, *>>(), Boolean::class.javaObjectType) } answers {
            capture("exists", firstArg(), secondArg())
            true
        }

        // when
        val thrown = assertThrows(IllegalStateException::class.java) { commands.add(7, 80L) }

        // then
        assertEquals("이미 찜한 상품입니다.", thrown.message)
        assertEquals(listOf("exists"), events)
        confirmVerified(clock)
        assertFalse(active)
    }

    @Test
    fun `SKU가 없을 때__찜을 추가하면__원래의 잘못된 입력 오류를 유지하고 삽입하지 않는다`() {
        // given
        val sku = rows()

        every { write.execute(any<TransactionCallback<Any>>()) } answers {
            inTransaction { firstArg<TransactionCallback<*>>().doInTransaction(SimpleTransactionStatus()) }
        }
        every { jdbc.queryForObject(any<String>(), any<Map<String, *>>(), Boolean::class.javaObjectType) } answers {
            capture("exists", firstArg(), secondArg())
            false
        }
        every { jdbc.query(any<String>(), any<Map<String, *>>(), any<ResultSetExtractor<Any>>()) } answers {
            capture("sku", firstArg(), secondArg())
            thirdArg<ResultSetExtractor<*>>().extractData(sku)
        }

        // when
        val thrown = assertThrows(IllegalArgumentException::class.java) { commands.add(7, 80L) }

        // then
        assertEquals("SKU를 찾을 수 없습니다: 80", thrown.message)
        assertEquals(listOf("exists", "sku"), events)
        confirmVerified(clock)
    }

    @Test
    fun `SKU가 null일 때__찜을 추가하면__중복 확인을 먼저 하고 SKU 조회 없이 잘못된 입력으로 실패한다`() {
        // given
        every { write.execute(any<TransactionCallback<Any>>()) } answers {
            inTransaction { firstArg<TransactionCallback<*>>().doInTransaction(SimpleTransactionStatus()) }
        }
        every { jdbc.queryForObject(any<String>(), any<Map<String, *>>(), Boolean::class.javaObjectType) } answers {
            capture("exists", firstArg(), secondArg())
            false
        }

        // when
        assertThrows(IllegalArgumentException::class.java) { commands.add(7, null) }

        // then
        assertEquals(listOf("exists"), events)
        assertNull(params.first()["sku"])
        confirmVerified(clock)
    }

    @Test
    fun `삽입이 실패할 때__찜을 추가하면__성공 결과 없이 원래의 실패를 전파한다`() {
        // given
        val sku = rows(map("sku_id", 80L, "product_id", 9L, "price", 1L))
        val failure = DataAccessResourceFailureException("synthetic SQL detail")

        every { clock.instant() } returns instant
        every { clock.zone } returns ZoneId.of("Asia/Seoul")
        every { write.execute(any<TransactionCallback<Any>>()) } answers {
            inTransaction { firstArg<TransactionCallback<*>>().doInTransaction(SimpleTransactionStatus()) }
        }
        every { jdbc.queryForObject(any<String>(), any<Map<String, *>>(), Boolean::class.javaObjectType) } answers {
            capture("exists", firstArg(), secondArg())
            false
        }
        every { jdbc.query(any<String>(), any<Map<String, *>>(), any<ResultSetExtractor<Any>>()) } answers {
            capture("sku", firstArg(), secondArg())
            thirdArg<ResultSetExtractor<*>>().extractData(sku)
        }
        every { jdbc.queryForObject(any<String>(), any<Map<String, *>>(), Long::class.javaObjectType) } throws failure

        // when
        val thrown = assertThrows(DataAccessResourceFailureException::class.java) { commands.add(7, 80L) }

        // then
        assertSame(failure, thrown)
        assertFalse(active)
    }

    @Test
    fun `생성된 ID가 없을 때__찜을 추가하면__ID를 꾸며 내지 않고 실패한다`() {
        // given
        val sku = rows(map("sku_id", 80L, "product_id", 9L, "price", 1L))

        every { clock.instant() } returns instant
        every { clock.zone } returns ZoneId.of("Asia/Seoul")
        every { write.execute(any<TransactionCallback<Any>>()) } answers {
            inTransaction { firstArg<TransactionCallback<*>>().doInTransaction(SimpleTransactionStatus()) }
        }
        every { jdbc.queryForObject(any<String>(), any<Map<String, *>>(), Boolean::class.javaObjectType) } answers {
            capture("exists", firstArg(), secondArg())
            false
        }
        every { jdbc.query(any<String>(), any<Map<String, *>>(), any<ResultSetExtractor<Any>>()) } answers {
            capture("sku", firstArg(), secondArg())
            thirdArg<ResultSetExtractor<*>>().extractData(sku)
        }
        every { jdbc.queryForObject(any<String>(), any<Map<String, *>>(), Long::class.javaObjectType) } answers {
            capture("insert", firstArg(), secondArg())
            null
        }

        // when
        // then
        assertThrows(IllegalStateException::class.java) { commands.add(7, 80L) }

        assertFalse(active)
    }

    @Test
    fun `소유한 쌍이 하나 있을 때__SKU로 찜을 삭제하면__선택한 ID와 회원과 SKU만 삭제한다`() {
        // given
        every { write.executeWithoutResult(any<Consumer<TransactionStatus>>()) } answers {
            inTransaction { firstArg<Consumer<TransactionStatus>>().accept(SimpleTransactionStatus()) }
        }
        every { jdbc.query(any<String>(), any<Map<String, *>>(), any<RowMapper<Any>>()) } answers {
            capture("find", firstArg(), secondArg())
            listOf(33L)
        }
        every { jdbc.update(any<String>(), any<Map<String, *>>()) } answers {
            capture("delete", firstArg(), secondArg())
            1
        }

        // when
        commands.removeByMemberAndSku(7, 80)

        // then
        assertEquals(listOf("find", "delete"), events)
        assertTrue(sql.first().contains("user_id=:member AND sku_id=:sku"))
        assertEquals("DELETE FROM pawbridge_store.wishlists WHERE id=:id AND user_id=:member AND sku_id=:sku", sql[1])
        assertEquals(mapOf("member" to 7L, "sku" to 80L, "id" to 33L), params[1])
        assertFalse(active)
    }

    @Test
    fun `쌍이 없을 때__SKU로 찜을 삭제하면__같은 잘못된 입력 오류를 내고 삭제하지 않는다`() {
        // given
        every { write.executeWithoutResult(any<Consumer<TransactionStatus>>()) } answers {
            inTransaction { firstArg<Consumer<TransactionStatus>>().accept(SimpleTransactionStatus()) }
        }
        every { jdbc.query(any<String>(), any<Map<String, *>>(), any<RowMapper<Any>>()) } answers {
            capture("find", firstArg(), secondArg())
            listOf<Long>()
        }

        // when
        val thrown = assertThrows(IllegalArgumentException::class.java) { commands.removeByMemberAndSku(7, 80) }

        // then
        assertEquals("찜을 찾을 수 없습니다.", thrown.message)
        assertEquals(listOf("find"), events)
    }

    @Test
    fun `이미 중복된 쌍이 있을 때__SKU로 찜을 삭제하면__하나도 전부도 삭제하지 않고 실패한다`() {
        // given
        every { write.executeWithoutResult(any<Consumer<TransactionStatus>>()) } answers {
            inTransaction { firstArg<Consumer<TransactionStatus>>().accept(SimpleTransactionStatus()) }
        }
        every { jdbc.query(any<String>(), any<Map<String, *>>(), any<RowMapper<Any>>()) } answers {
            capture("find", firstArg(), secondArg())
            listOf(33L, 34L)
        }

        // when
        // then
        assertThrows(IllegalStateException::class.java) { commands.removeByMemberAndSku(7, 80) }

        assertEquals(listOf("find"), events)
    }

    @Test
    fun `소유한 ID일 때__ID로 찜을 삭제하면__조회 없이 소유자 범위의 원자적 삭제 한 번을 실행한다`() {
        // given
        every { write.executeWithoutResult(any<Consumer<TransactionStatus>>()) } answers {
            inTransaction { firstArg<Consumer<TransactionStatus>>().accept(SimpleTransactionStatus()) }
        }
        every { jdbc.update(any<String>(), any<Map<String, *>>()) } answers {
            capture("delete", firstArg(), secondArg())
            1
        }

        // when
        commands.removeById(7, 33)

        // then
        assertEquals(listOf("delete"), events)
        assertEquals("DELETE FROM pawbridge_store.wishlists WHERE id=:id AND user_id=:member", sql.first())
        assertEquals(mapOf("member" to 7L, "id" to 33L), params.first())
        verify(exactly = 1) { write.executeWithoutResult(any<Consumer<TransactionStatus>>()) }
        verify(exactly = 1) { jdbc.update(sql.first(), params.first()) }
        confirmVerified(jdbc)
        confirmVerified(clock)
        assertFalse(active)
    }

    @Test
    fun `소유자가 다르거나 ID가 없을 때__ID로 찜을 삭제하면__조회 없이 0행이어도 같은 무동작이다`() {
        // given
        every { write.executeWithoutResult(any<Consumer<TransactionStatus>>()) } answers {
            inTransaction { firstArg<Consumer<TransactionStatus>>().accept(SimpleTransactionStatus()) }
        }
        every { jdbc.update(any<String>(), any<Map<String, *>>()) } answers {
            capture("delete", firstArg(), secondArg())
            0
        }

        // when
        assertDoesNotThrow(Executable { commands.removeById(8, 33) })

        // then
        assertEquals(listOf("delete"), events)
        assertEquals(mapOf("member" to 8L, "id" to 33L), params.first())
        assertEquals("DELETE FROM pawbridge_store.wishlists WHERE id=:id AND user_id=:member", sql.first())
        verify(exactly = 1) { jdbc.update(sql.first(), params.first()) }
        confirmVerified(jdbc)
        confirmVerified(clock)
        assertFalse(active)
    }

    @Test
    fun `이미 삭제된 ID일 때__재시도하거나 삭제 경합에서 질 때 삭제하면__0행은 계속 무동작이다`() {
        // given
        val affectedRows = mutableListOf(1, 0)

        every { write.executeWithoutResult(any<Consumer<TransactionStatus>>()) } answers {
            inTransaction { firstArg<Consumer<TransactionStatus>>().accept(SimpleTransactionStatus()) }
        }
        every { jdbc.update(any<String>(), any<Map<String, *>>()) } answers {
            capture("delete", firstArg(), secondArg())
            affectedRows.removeAt(0)
        }

        // when
        commands.removeById(7, 33)
        assertDoesNotThrow(Executable { commands.removeById(7, 33) })

        // then
        assertEquals(listOf("delete", "delete"), events)
        assertEquals(listOf(mapOf("member" to 7L, "id" to 33L), mapOf("member" to 7L, "id" to 33L)), params)
        verify(exactly = 2) { write.executeWithoutResult(any<Consumer<TransactionStatus>>()) }
        verify(exactly = 2) { jdbc.update(sql.first(), params.first()) }
        confirmVerified(jdbc)
        confirmVerified(clock)
        assertFalse(active)
    }

    @Test
    fun `선택한 쌍이 동시에 사라졌을 때__SKU로 찜을 삭제하면__삭제 성공을 주장하지 않고 실패한다`() {
        // given
        every { write.executeWithoutResult(any<Consumer<TransactionStatus>>()) } answers {
            inTransaction { firstArg<Consumer<TransactionStatus>>().accept(SimpleTransactionStatus()) }
        }
        every { jdbc.query(any<String>(), any<Map<String, *>>(), any<RowMapper<Any>>()) } answers {
            capture("find", firstArg(), secondArg())
            listOf(33L)
        }
        every { jdbc.update(any<String>(), any<Map<String, *>>()) } answers {
            capture("delete", firstArg(), secondArg())
            0
        }

        // when
        // then
        assertThrows(IllegalStateException::class.java) { commands.removeByMemberAndSku(7, 80) }

        assertFalse(active)
    }

    private fun <T> inTransaction(body: () -> T): T {
        active = true
        try {
            return body()
        } finally {
            active = false
        }
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
            val resultSet = mockk<ResultSet>()
            every { resultSet.next() } answers { i.incrementAndGet() < values.size }
            every { resultSet.getLong(any<String>()) } answers {
                val value = values[i.get()][firstArg<String>()]
                if (value == null) 0L else (value as Number).toLong()
            }
            every { resultSet.getString(any<String>()) } answers { values[i.get()][firstArg<String>()] as String? }
            every { resultSet.getObject(any<String>()) } answers { values[i.get()][firstArg<String>()] }
            return resultSet
        }
    }
}
