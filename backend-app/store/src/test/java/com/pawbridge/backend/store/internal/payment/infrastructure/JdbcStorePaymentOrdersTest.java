package com.pawbridge.backend.store.internal.payment.infrastructure;

import com.pawbridge.backend.store.api.StorePaymentOrders;
import java.sql.ResultSet;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class JdbcStorePaymentOrdersTest {
    @ParameterizedTest @ValueSource(booleans = {false, true})
    void given__an_original_uuid_row_with_nullable_amount_and_status__when__looking_up__then__return_four_fields_in_reader_transaction(boolean nullable) throws Exception {
        var jdbc = mock(JdbcTemplate.class);
        var manager = mock(PlatformTransactionManager.class);
        var status = mock(TransactionStatus.class);
        when(manager.getTransaction(any())).thenReturn(status);
        var reads = new TransactionTemplate(manager); reads.setReadOnly(true);
        var row = mock(ResultSet.class);
        when(row.getObject("id", Long.class)).thenReturn(8L);
        when(row.getString("order_uuid")).thenReturn("synthetic-order");
        when(row.getObject("total_amount", Long.class)).thenReturn(nullable ? null : 200L);
        when(row.getString("status")).thenReturn(nullable ? null : "PENDING");
        doAnswer(call -> {
            String sql = call.getArgument(0);
            assertThat(sql).contains("SELECT id,order_uuid,total_amount,status FROM pawbridge_store.orders WHERE order_uuid=?")
                    .doesNotContain("user_id", "PENDING", "FOR UPDATE");
            RowMapper<StorePaymentOrders.Order> mapper = call.getArgument(1);
            return mapper.mapRow(row, 0);
        }).when(jdbc).queryForObject(anyString(), any(RowMapper.class), eq("synthetic-order"));
        var result = new JdbcStorePaymentOrders(jdbc, reads).getOrder("synthetic-order");
        assertThat(result).isEqualTo(new StorePaymentOrders.Order(8L, "synthetic-order", nullable ? null : 200L, nullable ? null : "PENDING"));
        var order = inOrder(manager, jdbc);
        order.verify(manager).getTransaction(reads);
        order.verify(jdbc).queryForObject(anyString(), any(RowMapper.class), eq("synthetic-order"));
        order.verify(manager).commit(status); order.verifyNoMoreInteractions();
    }

    @Test
    void given__an_active_writer_transaction__when__looking_up_on_global_reader__then__use_separate_read_only_connection_and_restore_writer_state() throws Exception {
        var reader = mock(javax.sql.DataSource.class); var writer = mock(javax.sql.DataSource.class);
        var readerConnection = mock(java.sql.Connection.class); var writerConnection = mock(java.sql.Connection.class);
        when(reader.getConnection()).thenReturn(readerConnection); when(writer.getConnection()).thenReturn(writerConnection);
        when(readerConnection.getAutoCommit()).thenReturn(true); when(writerConnection.getAutoCommit()).thenReturn(true);
        var reads = new TransactionTemplate(new org.springframework.jdbc.support.JdbcTransactionManager(reader)); reads.setReadOnly(true);
        var writes = new TransactionTemplate(new org.springframework.jdbc.support.JdbcTransactionManager(writer));
        var jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(), any(RowMapper.class), eq("synthetic-order"))).thenAnswer(call -> {
            assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.hasResource(reader)).isTrue();
            assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.hasResource(writer)).isTrue();
            assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isCurrentTransactionReadOnly()).isTrue();
            return new StorePaymentOrders.Order(8L, "synthetic-order", 200L, "PENDING");
        });
        var orders = new JdbcStorePaymentOrders(jdbc, reads);
        writes.execute(status -> {
            assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.hasResource(writer)).isTrue();
            orders.getOrder("synthetic-order");
            assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.hasResource(reader)).isFalse();
            assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.hasResource(writer)).isTrue();
            assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isCurrentTransactionReadOnly()).isFalse();
            return null;
        });
        verify(readerConnection).setReadOnly(true);
        verify(readerConnection).commit(); verify(writerConnection).commit();
        assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.hasResource(reader)).isFalse();
        assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.hasResource(writer)).isFalse();
    }

    @Test
    void given__an_unknown_uuid__when__looking_up__then__preserve_original_not_found_exception() {
        var jdbc = mock(JdbcTemplate.class); var manager = mock(PlatformTransactionManager.class);
        var status = mock(TransactionStatus.class); when(manager.getTransaction(any())).thenReturn(status);
        when(jdbc.queryForObject(anyString(), any(RowMapper.class), eq("missing-order"))).thenThrow(new EmptyResultDataAccessException(1));
        var reads = new TransactionTemplate(manager); reads.setReadOnly(true);
        assertThatThrownBy(() -> new JdbcStorePaymentOrders(jdbc, reads).getOrder("missing-order"))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("Order not found: missing-order");
        verify(manager).rollback(status);
    }

    @Test
    void given__a_reader_failure__when__looking_up__then__propagate_without_replacing_it_with_not_found() {
        var jdbc = mock(JdbcTemplate.class); var manager = mock(PlatformTransactionManager.class);
        var status = mock(TransactionStatus.class); when(manager.getTransaction(any())).thenReturn(status);
        var failure = new DataAccessResourceFailureException("synthetic-reader-failure");
        when(jdbc.queryForObject(anyString(), any(RowMapper.class), eq("synthetic-order"))).thenThrow(failure);
        var reads = new TransactionTemplate(manager); reads.setReadOnly(true);
        assertThatThrownBy(() -> new JdbcStorePaymentOrders(jdbc, reads).getOrder("synthetic-order")).isSameAs(failure);
        verify(manager).rollback(status);
    }
}
