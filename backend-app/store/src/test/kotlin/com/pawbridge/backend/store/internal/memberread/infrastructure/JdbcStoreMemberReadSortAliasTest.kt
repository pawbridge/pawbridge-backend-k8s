package com.pawbridge.backend.store.internal.memberread.infrastructure

import com.pawbridge.backend.store.api.StoreMemberReads
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyMap
import org.mockito.ArgumentMatchers.anyString
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoMoreInteractions
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
    fun given__original_sku_price_alias__when__reading_wishlists__then__use_existing_numeric_sort_and_preserve_requested_property(
        property: String,
        ascending: Boolean,
        direction: String,
    ) {
        val jdbc = mock(NamedParameterJdbcOperations::class.java)
        val transaction = mock(TransactionOperations::class.java)
        val active = AtomicBoolean()
        `when`(transaction.execute(any<TransactionCallback<Any>>())).thenAnswer { call ->
            active.set(true)
            try {
                call.getArgument<TransactionCallback<*>>(0).doInTransaction(SimpleTransactionStatus())
            } finally {
                active.set(false)
            }
        }
        `when`(jdbc.query(anyString(), anyMap<String, Any>(), any<ResultSetExtractor<Any>>())).thenAnswer {
            assertTrue(active.get())
            listOf<Any>()
        }
        val requested = listOf(StoreMemberReads.Order(property, ascending, true))
        val request = StoreMemberReads.Request(0, 20, requested)

        val result = JdbcStoreMemberReads(jdbc, transaction).wishlists(7L, request)

        val sql = ArgumentCaptor.forClass(String::class.java)
        verify(jdbc).query(
            sql.capture(),
            eq(mapOf<String, Any>("member" to 7L, "size" to 20, "offset" to 0L)),
            any<ResultSetExtractor<Any>>(),
        )
        val order = "ORDER BY s.price $direction"
        assertEquals(2, sql.value.split(Regex(order)).size - 1) // parent selection and expanded result order
        assertTrue(sql.value.contains("$order LIMIT :size OFFSET :offset"))
        assertTrue(sql.value.endsWith(order))
        assertFalse(sql.value.contains("lower("))
        assertFalse(sql.value.contains(property))
        assertEquals(StoreMemberReads.Page<StoreMemberReads.Wishlist>(listOf(), 0, 20, 0L, requested), result)
        verify(transaction, times(1)).execute(any<TransactionCallback<Any>>())
        verifyNoMoreInteractions(jdbc, transaction)
        assertFalse(active.get())
    }
}
