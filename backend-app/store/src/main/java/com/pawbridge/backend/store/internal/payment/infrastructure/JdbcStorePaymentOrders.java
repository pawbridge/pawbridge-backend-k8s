package com.pawbridge.backend.store.internal.payment.infrastructure;

import com.pawbridge.backend.store.api.StorePaymentOrders;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/** The original Store UUID lookup on the separately owned global reader transaction. */
public final class JdbcStorePaymentOrders implements StorePaymentOrders {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate reads;

    public JdbcStorePaymentOrders(JdbcTemplate jdbc, TransactionTemplate reads) {
        this.jdbc = jdbc;
        this.reads = reads;
    }

    @Override public Order getOrder(String orderUuid) {
        return reads.execute(status -> {
            try {
                return jdbc.queryForObject("""
                        SELECT id,order_uuid,total_amount,status FROM pawbridge_store.orders WHERE order_uuid=?
                        """, (row, index) -> new Order(row.getObject("id", Long.class), row.getString("order_uuid"),
                        row.getObject("total_amount", Long.class), row.getString("status")), orderUuid);
            } catch (EmptyResultDataAccessException missing) {
                throw new IllegalArgumentException("Order not found: " + orderUuid);
            }
        });
    }
}
