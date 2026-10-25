package com.pawbridge.backend.store.internal.payment.infrastructure

import com.pawbridge.backend.store.api.StorePaymentOrders
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyString
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.inOrder
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.mockito.Mockito.verify
import org.springframework.dao.DataAccessResourceFailureException
import org.springframework.dao.EmptyResultDataAccessException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.support.JdbcTransactionManager
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionStatus
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.transaction.support.TransactionTemplate
import java.sql.Connection
import java.sql.ResultSet
import javax.sql.DataSource

class JdbcStorePaymentOrdersTest {
    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun given__an_original_uuid_row_with_nullable_amount_and_status__when__looking_up__then__return_five_fields_in_reader_transaction(
        nullable: Boolean,
    ) {
        val jdbc = mock(JdbcTemplate::class.java)
        val manager = mock(PlatformTransactionManager::class.java)
        val status = mock(TransactionStatus::class.java)
        `when`(manager.getTransaction(any())).thenReturn(status)
        val reads = TransactionTemplate(manager)
        reads.isReadOnly = true
        val row = mock(ResultSet::class.java)
        `when`(row.getObject("id", Long::class.javaObjectType)).thenReturn(8L)
        `when`(row.getString("order_uuid")).thenReturn("synthetic-order")
        `when`(row.getObject("user_id", Long::class.javaObjectType)).thenReturn(7L)
        `when`(row.getObject("total_amount", Long::class.javaObjectType)).thenReturn(if (nullable) null else 200L)
        `when`(row.getString("status")).thenReturn(if (nullable) null else "PENDING")
        doAnswer { call ->
            val sql = call.getArgument<String>(0)
            assertThat(sql)
                .contains("SELECT id,order_uuid,user_id,total_amount,status FROM pawbridge_store.orders WHERE order_uuid=?")
                .doesNotContain("PENDING", "FOR UPDATE")
            val mapper = call.getArgument<RowMapper<StorePaymentOrders.Order>>(1)
            mapper.mapRow(row, 0)
        }.`when`(jdbc).queryForObject(anyString(), any<RowMapper<StorePaymentOrders.Order>>(), eq("synthetic-order"))

        val result = JdbcStorePaymentOrders(jdbc, reads).getOrder("synthetic-order")

        assertThat(result).isEqualTo(
            StorePaymentOrders.Order(8L, "synthetic-order", 7L, if (nullable) null else 200L, if (nullable) null else "PENDING"),
        )
        val order = inOrder(manager, jdbc)
        order.verify(manager).getTransaction(reads)
        order.verify(jdbc).queryForObject(anyString(), any<RowMapper<StorePaymentOrders.Order>>(), eq("synthetic-order"))
        order.verify(manager).commit(status)
        order.verifyNoMoreInteractions()
    }

    @Test
    fun given__an_active_writer_transaction__when__looking_up_on_global_reader__then__use_separate_read_only_connection_and_restore_writer_state() {
        val reader = mock(DataSource::class.java)
        val writer = mock(DataSource::class.java)
        val readerConnection = mock(Connection::class.java)
        val writerConnection = mock(Connection::class.java)
        `when`(reader.connection).thenReturn(readerConnection)
        `when`(writer.connection).thenReturn(writerConnection)
        `when`(readerConnection.autoCommit).thenReturn(true)
        `when`(writerConnection.autoCommit).thenReturn(true)
        val reads = TransactionTemplate(JdbcTransactionManager(reader))
        reads.isReadOnly = true
        val writes = TransactionTemplate(JdbcTransactionManager(writer))
        val jdbc = mock(JdbcTemplate::class.java)
        `when`(jdbc.queryForObject(anyString(), any<RowMapper<StorePaymentOrders.Order>>(), eq("synthetic-order"))).thenAnswer {
            assertThat(TransactionSynchronizationManager.hasResource(reader)).isTrue()
            assertThat(TransactionSynchronizationManager.hasResource(writer)).isTrue()
            assertThat(TransactionSynchronizationManager.isCurrentTransactionReadOnly()).isTrue()
            StorePaymentOrders.Order(8L, "synthetic-order", 7L, 200L, "PENDING")
        }
        val orders = JdbcStorePaymentOrders(jdbc, reads)

        writes.execute<Any?> {
            assertThat(TransactionSynchronizationManager.hasResource(writer)).isTrue()
            orders.getOrder("synthetic-order")
            assertThat(TransactionSynchronizationManager.hasResource(reader)).isFalse()
            assertThat(TransactionSynchronizationManager.hasResource(writer)).isTrue()
            assertThat(TransactionSynchronizationManager.isCurrentTransactionReadOnly()).isFalse()
            null
        }

        verify(readerConnection).setReadOnly(true)
        verify(readerConnection).commit()
        verify(writerConnection).commit()
        assertThat(TransactionSynchronizationManager.hasResource(reader)).isFalse()
        assertThat(TransactionSynchronizationManager.hasResource(writer)).isFalse()
    }

    @Test
    fun given__an_unknown_uuid__when__looking_up__then__preserve_original_not_found_exception() {
        val jdbc = mock(JdbcTemplate::class.java)
        val manager = mock(PlatformTransactionManager::class.java)
        val status = mock(TransactionStatus::class.java)
        `when`(manager.getTransaction(any())).thenReturn(status)
        `when`(jdbc.queryForObject(anyString(), any<RowMapper<StorePaymentOrders.Order>>(), eq("missing-order")))
            .thenThrow(EmptyResultDataAccessException(1))
        val reads = TransactionTemplate(manager)
        reads.isReadOnly = true

        assertThatThrownBy { JdbcStorePaymentOrders(jdbc, reads).getOrder("missing-order") }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("Order not found: missing-order")

        verify(manager).rollback(status)
    }

    @Test
    fun given__a_reader_failure__when__looking_up__then__propagate_without_replacing_it_with_not_found() {
        val jdbc = mock(JdbcTemplate::class.java)
        val manager = mock(PlatformTransactionManager::class.java)
        val status = mock(TransactionStatus::class.java)
        `when`(manager.getTransaction(any())).thenReturn(status)
        val failure = DataAccessResourceFailureException("synthetic-reader-failure")
        `when`(jdbc.queryForObject(anyString(), any<RowMapper<StorePaymentOrders.Order>>(), eq("synthetic-order")))
            .thenThrow(failure)
        val reads = TransactionTemplate(manager)
        reads.isReadOnly = true

        assertThatThrownBy { JdbcStorePaymentOrders(jdbc, reads).getOrder("synthetic-order") }.isSameAs(failure)

        verify(manager).rollback(status)
    }
}
