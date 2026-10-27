package com.pawbridge.backend.contract

import javax.sql.DataSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.support.JdbcTransactionManager
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate

/**
 * Base of the store repository contracts: the application's own pools and the rows the store queries read.
 *
 * Every table the test changes is restored by [ApiContractTestBase], so tests may seed any ids.
 */
abstract class StoreRepositoryContractBase : ApiContractTestBase() {
    @Autowired
    @Qualifier("dataSource")
    protected lateinit var source: DataSource

    @Autowired
    @Qualifier("transactionManager")
    protected lateinit var manager: PlatformTransactionManager

    @Autowired
    @Qualifier("writeDataSource")
    protected lateinit var writeSource: DataSource

    @Autowired
    @Qualifier("writeTransactionManager")
    protected lateinit var writeManager: JdbcTransactionManager

    protected fun readJdbc() = NamedParameterJdbcTemplate(JdbcTemplate(source))

    protected fun writeJdbc() = NamedParameterJdbcTemplate(JdbcTemplate(writeSource))

    protected fun readTransaction() = TransactionTemplate(manager).apply { isReadOnly = true }

    protected fun writeTransaction() = TransactionTemplate(writeManager)

    protected fun seedCategory(id: Long, name: String, parent: Long? = null, description: String? = null) {
        execute("INSERT INTO pawbridge_store.categories(id, name, parent_id, description) VALUES (?, ?, ?, ?)", id, name, parent, description)
    }

    protected fun seedProduct(
        id: Long,
        name: String,
        status: String = "ACTIVE",
        category: Long? = null,
        createdAt: String = "2026-03-01 00:00:00",
        description: String? = "설명 $id",
    ) {
        execute(
            "INSERT INTO pawbridge_store.products(id, name, status, view_count, category_id, created_at, updated_at, description, image_url) " +
                "VALUES (?, ?, ?, 3, ?, ?::timestamp, ?::timestamp, ?, ?)",
            id, name, status, category, createdAt, createdAt, description, "https://fixture.invalid/p$id.jpg",
        )
    }

    protected fun seedSku(id: Long, product: Long, code: String = "SKU-$id", price: Long = 1_000, stock: Int = 5) {
        execute(
            "INSERT INTO pawbridge_store.product_skus(id, product_id, sku_code, price, stock_quantity, created_at, updated_at) " +
                "VALUES (?, ?, ?, ?, ?, '2026-03-01 00:00:00', '2026-03-01 00:00:00')",
            id, product, code, price, stock,
        )
    }

    protected fun seedOption(sku: Long, groupId: Long, group: String, valueId: Long, value: String) {
        execute("INSERT INTO pawbridge_store.option_groups(id, name) VALUES (?, ?) ON CONFLICT (id) DO NOTHING", groupId, group)
        execute("INSERT INTO pawbridge_store.option_values(id, name, option_group_id) VALUES (?, ?, ?) ON CONFLICT (id) DO NOTHING", valueId, value, groupId)
        execute("INSERT INTO pawbridge_store.sku_values(option_value_id, product_sku_id) VALUES (?, ?)", valueId, sku)
    }

    protected fun seedOrder(id: Long, user: Long, uuid: String = "order-$id", status: String = "PAID", createdAt: String = "2026-03-01 00:00:00", total: Long = 5_000) {
        execute(
            "INSERT INTO pawbridge_store.orders(id, order_uuid, user_id, total_amount, status, delivery_status, delivery_address, receiver_name, receiver_phone, created_at, updated_at) " +
                "VALUES (?, ?, ?, ?, ?, 'READY', '합성 주소', '수령인', '010-0000-0000', ?::timestamp, ?::timestamp)",
            id, uuid, user, total, status, createdAt, createdAt,
        )
    }

    protected fun seedWishlist(id: Long, user: Long, sku: Long, createdAt: String = "2026-03-01 00:00:00") {
        execute("INSERT INTO pawbridge_store.wishlists(id, user_id, sku_id, created_at, updated_at) VALUES (?, ?, ?, ?::timestamp, ?::timestamp)", id, user, sku, createdAt, createdAt)
    }
}
