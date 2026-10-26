package com.pawbridge.backend.store.internal.memberread.infrastructure

import com.pawbridge.backend.store.api.StoreMemberReads
import io.mockk.confirmVerified
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.springframework.jdbc.core.ResultSetExtractor
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcOperations
import org.springframework.transaction.support.SimpleTransactionStatus
import org.springframework.transaction.support.TransactionCallback
import org.springframework.transaction.support.TransactionOperations
import java.util.concurrent.atomic.AtomicBoolean

class JdbcStoreMemberReadSortAliasTest {
    // Original Wishlist.sku -> ProductSKU.price, resolved by Spring Data's PropertyPath.
    // These literal aliases cover only that existing to-one scalar expression, not arbitrary JPA paths.
    @ParameterizedTest
    @CsvSource("skuPrice,true,ASC", "skuPrice,false,DESC", "sku_price,true,ASC", "sku_price,false,DESC")
    fun `원본 SKU 가격 별칭으로 정렬을 요청했을 때__위시리스트를 읽으면__기존 숫자 정렬을 쓰고 요청한 속성을 보존한다`(
        property: String,
        ascending: Boolean,
        direction: String,
    ) {
        // given
        val jdbc = mockk<NamedParameterJdbcOperations>()
        val transaction = mockk<TransactionOperations>()
        val active = AtomicBoolean()
        val requested = listOf(StoreMemberReads.Order(property, ascending, true))
        val request = StoreMemberReads.Request(0, 20, requested)

        every { transaction.execute(any<TransactionCallback<Any>>()) } answers {
            active.set(true)
            try {
                firstArg<TransactionCallback<*>>().doInTransaction(SimpleTransactionStatus())
            } finally {
                active.set(false)
            }
        }
        every { jdbc.query(any<String>(), any<Map<String, *>>(), any<ResultSetExtractor<Any>>()) } answers {
            assertTrue(active.get())
            listOf<Any>()
        }

        // when
        val result = JdbcStoreMemberReads(jdbc, transaction).wishlists(7L, request)

        // then
        val sql = slot<String>()
        verify(exactly = 1) {
            jdbc.query(
                capture(sql),
                mapOf<String, Any>("member" to 7L, "size" to 20, "offset" to 0L),
                any<ResultSetExtractor<Any>>(),
            )
        }
        val order = "ORDER BY s.price $direction"
        assertEquals(2, sql.captured.split(Regex(order)).size - 1) // parent selection and expanded result order
        assertTrue(sql.captured.contains("$order LIMIT :size OFFSET :offset"))
        assertTrue(sql.captured.endsWith(order))
        assertFalse(sql.captured.contains("lower("))
        assertFalse(sql.captured.contains(property))
        assertEquals(StoreMemberReads.Page<StoreMemberReads.Wishlist>(listOf(), 0, 20, 0L, requested), result)
        verify(exactly = 1) { transaction.execute(any<TransactionCallback<Any>>()) }
        confirmVerified(jdbc, transaction)
        assertFalse(active.get())
    }
}
