package com.pawbridge.backend.store.internal.payment.infrastructure

import com.pawbridge.backend.store.api.StorePaymentOrders
import org.springframework.dao.EmptyResultDataAccessException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.transaction.support.TransactionTemplate

/** The original Store UUID lookup on the separately owned global reader transaction. */
class JdbcStorePaymentOrders(
    private val jdbc: JdbcTemplate,
    private val reads: TransactionTemplate,
) : StorePaymentOrders {

    override fun getOrder(orderUuid: String?): StorePaymentOrders.Order =
        reads.execute {
            try {
                jdbc.queryForObject(
                    SELECT_BY_UUID,
                    RowMapper { row, _ ->
                        StorePaymentOrders.Order(
                            row.getObject("id", Long::class.javaObjectType),
                            row.getString("order_uuid"),
                            row.getObject("user_id", Long::class.javaObjectType),
                            row.getObject("total_amount", Long::class.javaObjectType),
                            row.getString("status"),
                        )
                    },
                    orderUuid,
                )
            } catch (missing: EmptyResultDataAccessException) {
                throw IllegalArgumentException("Order not found: $orderUuid")
            }
        }!!

    private companion object {
        private val SELECT_BY_UUID = """
            SELECT id,order_uuid,user_id,total_amount,status FROM pawbridge_store.orders WHERE order_uuid=?
        """.trimIndent() + "\n"
    }
}
