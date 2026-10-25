package com.pawbridge.backend.store.internal.payment.infrastructure

import com.pawbridge.backend.store.api.StorePaymentOrders
import io.mockk.every
import io.mockk.justRun
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifySequence
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
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
    fun `UUID 주문 행에 금액과 상태가 null일 수 있을 때__조회하면__리더 트랜잭션 안에서 다섯 필드를 돌려준다`(
        nullable: Boolean,
    ) {
        // given
        val jdbc = mockk<JdbcTemplate>()
        val manager = mockk<PlatformTransactionManager>()
        val status = mockk<TransactionStatus>()
        val reads = TransactionTemplate(manager)
        reads.isReadOnly = true
        val row = mockk<ResultSet>()

        every { manager.getTransaction(any()) } returns status
        justRun { manager.commit(any()) }
        every { row.getObject("id", Long::class.javaObjectType) } returns 8L
        every { row.getString("order_uuid") } returns "synthetic-order"
        every { row.getObject("user_id", Long::class.javaObjectType) } returns 7L
        every { row.getObject("total_amount", Long::class.javaObjectType) } returns if (nullable) null else 200L
        every { row.getString("status") } returns if (nullable) null else "PENDING"
        every {
            jdbc.queryForObject(any<String>(), any<RowMapper<StorePaymentOrders.Order>>(), "synthetic-order")
        } answers {
            val sql = firstArg<String>()
            assertThat(sql)
                .contains("SELECT id,order_uuid,user_id,total_amount,status FROM pawbridge_store.orders WHERE order_uuid=?")
                .doesNotContain("PENDING", "FOR UPDATE")
            val mapper = secondArg<RowMapper<StorePaymentOrders.Order>>()
            mapper.mapRow(row, 0)
        }

        // when
        val result = JdbcStorePaymentOrders(jdbc, reads).getOrder("synthetic-order")

        // then
        assertThat(result).isEqualTo(
            StorePaymentOrders.Order(8L, "synthetic-order", 7L, if (nullable) null else 200L, if (nullable) null else "PENDING"),
        )
        verifySequence {
            manager.getTransaction(reads)
            jdbc.queryForObject(any<String>(), any<RowMapper<StorePaymentOrders.Order>>(), "synthetic-order")
            manager.commit(status)
        }
    }

    @Test
    fun `활성 라이터 트랜잭션이 있을 때__글로벌 리더로 조회하면__별도의 읽기 전용 커넥션을 쓰고 라이터 상태를 복원한다`() {
        // given
        val reader = mockk<DataSource>()
        val writer = mockk<DataSource>()
        val readerConnection = mockk<Connection>()
        val writerConnection = mockk<Connection>()
        val reads = TransactionTemplate(JdbcTransactionManager(reader))
        reads.isReadOnly = true
        val writes = TransactionTemplate(JdbcTransactionManager(writer))
        val jdbc = mockk<JdbcTemplate>()
        val orders = JdbcStorePaymentOrders(jdbc, reads)

        every { reader.connection } returns readerConnection
        every { writer.connection } returns writerConnection
        every { readerConnection.autoCommit } returns true
        every { writerConnection.autoCommit } returns true
        justRun { readerConnection.autoCommit = any() }
        justRun { writerConnection.autoCommit = any() }
        justRun { readerConnection.isReadOnly = any() }
        justRun { writerConnection.isReadOnly = any() }
        justRun { readerConnection.commit() }
        justRun { writerConnection.commit() }
        justRun { readerConnection.close() }
        justRun { writerConnection.close() }
        every {
            jdbc.queryForObject(any<String>(), any<RowMapper<StorePaymentOrders.Order>>(), "synthetic-order")
        } answers {
            assertThat(TransactionSynchronizationManager.hasResource(reader)).isTrue()
            assertThat(TransactionSynchronizationManager.hasResource(writer)).isTrue()
            assertThat(TransactionSynchronizationManager.isCurrentTransactionReadOnly()).isTrue()
            StorePaymentOrders.Order(8L, "synthetic-order", 7L, 200L, "PENDING")
        }

        // when
        writes.execute<Any?> {
            assertThat(TransactionSynchronizationManager.hasResource(writer)).isTrue()
            orders.getOrder("synthetic-order")
            assertThat(TransactionSynchronizationManager.hasResource(reader)).isFalse()
            assertThat(TransactionSynchronizationManager.hasResource(writer)).isTrue()
            assertThat(TransactionSynchronizationManager.isCurrentTransactionReadOnly()).isFalse()
            null
        }

        // then
        verify(exactly = 1) { readerConnection.isReadOnly = true }
        verify(exactly = 1) { readerConnection.commit() }
        verify(exactly = 1) { writerConnection.commit() }
        assertThat(TransactionSynchronizationManager.hasResource(reader)).isFalse()
        assertThat(TransactionSynchronizationManager.hasResource(writer)).isFalse()
    }

    @Test
    fun `알 수 없는 UUID일 때__조회하면__원래의 not found 예외를 유지한다`() {
        // given
        val jdbc = mockk<JdbcTemplate>()
        val manager = mockk<PlatformTransactionManager>()
        val status = mockk<TransactionStatus>()
        val reads = TransactionTemplate(manager)
        reads.isReadOnly = true

        every { manager.getTransaction(any()) } returns status
        justRun { manager.rollback(any()) }
        every {
            jdbc.queryForObject(any<String>(), any<RowMapper<StorePaymentOrders.Order>>(), "missing-order")
        } throws EmptyResultDataAccessException(1)

        // when
        // then
        assertThatThrownBy { JdbcStorePaymentOrders(jdbc, reads).getOrder("missing-order") }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("Order not found: missing-order")

        verify(exactly = 1) { manager.rollback(status) }
    }

    @Test
    fun `리더 조회가 실패할 때__조회하면__not found로 바꾸지 않고 그대로 전파한다`() {
        // given
        val jdbc = mockk<JdbcTemplate>()
        val manager = mockk<PlatformTransactionManager>()
        val status = mockk<TransactionStatus>()
        val failure = DataAccessResourceFailureException("synthetic-reader-failure")
        val reads = TransactionTemplate(manager)
        reads.isReadOnly = true

        every { manager.getTransaction(any()) } returns status
        justRun { manager.rollback(any()) }
        every {
            jdbc.queryForObject(any<String>(), any<RowMapper<StorePaymentOrders.Order>>(), "synthetic-order")
        } throws failure

        // when
        // then
        assertThatThrownBy { JdbcStorePaymentOrders(jdbc, reads).getOrder("synthetic-order") }.isSameAs(failure)

        verify(exactly = 1) { manager.rollback(status) }
    }
}
