package com.pawbridge.backend.store.internal.memberread.infrastructure

import com.pawbridge.backend.store.api.StoreMemberReadUnavailableException
import com.pawbridge.backend.store.api.StoreMemberReads
import com.pawbridge.backend.store.internal.memberread.application.MemberReadPages
import org.springframework.dao.DataAccessException
import org.springframework.jdbc.core.ResultSetExtractor
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcOperations
import org.springframework.transaction.TransactionException
import org.springframework.transaction.support.TransactionOperations
import java.time.LocalDateTime
import java.util.Optional
import java.util.function.LongSupplier

/** Three persisted MyPage reads only. No Redis cart, synchronization, writes or external calls. */
class JdbcStoreMemberReads(
    private val jdbc: NamedParameterJdbcOperations,
    private val read: TransactionOperations,
) : StoreMemberReads {

    override fun wishlists(
        memberId: Long,
        request: StoreMemberReads.Request,
    ): StoreMemberReads.Page<StoreMemberReads.Wishlist> {
        val order = orderBy(request, true)
        return execute {
            val params = parameters(memberId, request)
            // Select page parents before expanding options, in the same statement snapshot as the detail rows.
            val sql = "WITH selected AS (SELECT w.id " + WISHLIST_FROM + " WHERE w.user_id=:member" + order +
                " LIMIT :size OFFSET :offset) " + WISHLIST_DETAILS + order
            val content: List<StoreMemberReads.Wishlist> = jdbc.query(
                sql,
                params,
                ResultSetExtractor { rs ->
                    val result = LinkedHashMap<Long, StoreMemberReads.Wishlist>()
                    while (rs.next()) {
                        val id = rs.getLong("wishlist_id")
                        var item = result[id]
                        if (item == null) {
                            item = StoreMemberReads.Wishlist(
                                id,
                                rs.getLong("sku_id"),
                                rs.getString("sku_code"),
                                rs.getLong("product_id"),
                                rs.getString("product_name"),
                                rs.getString("description"),
                                rs.getString("image_url"),
                                rs.getString("status"),
                                rs.getLong("price"),
                                HashMap<String?, String?>(),
                                rs.getObject("category_id", Long::class.javaObjectType),
                                rs.getString("category_name"),
                                rs.getObject("created_at", LocalDateTime::class.java),
                            )
                            result[id] = item
                        }
                        if (rs.getObject("option_value_id") != null) {
                            item.options!!.put(rs.getString("group_name"), rs.getString("value_name"))
                        }
                    }
                    ArrayList(result.values)
                },
            )!!
            MemberReadPages.page(content, request, LongSupplier { count("wishlists", memberId) })
        }
    }

    override fun orders(
        memberId: Long,
        request: StoreMemberReads.Request,
    ): StoreMemberReads.Page<StoreMemberReads.Purchase> {
        val order = orderBy(request, false)
        return execute {
            val params = parameters(memberId, request)
            val headers: List<StoreMemberReads.Purchase> = jdbc.query(
                ORDER_HEADERS + order + " LIMIT :size OFFSET :offset",
                params,
                RowMapper { rs, _ ->
                    StoreMemberReads.Purchase(
                        rs.getLong("id"),
                        rs.getString("order_uuid"),
                        rs.getLong("total_amount"),
                        rs.getString("status"),
                        rs.getString("delivery_address"),
                        rs.getString("delivery_status"),
                        ArrayList<StoreMemberReads.OrderItem>(),
                        rs.getObject("created_at", LocalDateTime::class.java),
                    )
                },
            )
            if (headers.isNotEmpty()) {
                params["ids"] = headers.stream().map { it.orderId }.toList()
                val items: Map<Long, List<StoreMemberReads.OrderItem>> = jdbc.query(
                    ORDER_ITEMS,
                    params,
                    ResultSetExtractor { rs ->
                        val result = HashMap<Long, MutableList<StoreMemberReads.OrderItem>>()
                        while (rs.next()) {
                            result.computeIfAbsent(rs.getLong("order_id")) { ArrayList() }.add(
                                StoreMemberReads.OrderItem(
                                    rs.getLong("item_id"),
                                    rs.getLong("product_sku_id"),
                                    rs.getString("product_name"),
                                    rs.getString("sku_code"),
                                    rs.getLong("price"),
                                    rs.getInt("quantity"),
                                ),
                            )
                        }
                        result
                    },
                )!!
                for (header in headers) {
                    header.items!!.addAll(items.getOrDefault(header.orderId, listOf()))
                }
            }
            MemberReadPages.page(headers, request, LongSupplier { count("orders", memberId) })
        }
    }

    override fun cart(memberId: Long): Optional<StoreMemberReads.Cart> =
        execute {
            jdbc.query(
                CART,
                mapOf("member" to memberId),
                ResultSetExtractor { rs ->
                    var cart: StoreMemberReads.Cart? = null
                    while (rs.next()) {
                        val id = rs.getLong("cart_id")
                        if (cart == null) {
                            cart = StoreMemberReads.Cart(
                                id,
                                rs.getLong("user_id"),
                                ArrayList<StoreMemberReads.CartItem>(),
                                rs.getObject("created_at", LocalDateTime::class.java),
                            )
                        } else if (cart.cartId != id) {
                            throw IllegalStateException("Multiple carts for one member")
                        }
                        if (rs.getObject("item_id") == null) {
                            continue
                        }
                        val exists = rs.getObject("live_sku_id") != null
                        val price = if (exists) rs.getLong("price") else 0L
                        val quantity = rs.getInt("quantity")
                        cart.items!!.add(
                            StoreMemberReads.CartItem(
                                rs.getLong("item_id"),
                                rs.getLong("product_sku_id"),
                                if (exists) rs.getString("product_name") else null,
                                if (exists) rs.getString("sku_code") else null,
                                price,
                                quantity,
                                if (exists) price * quantity else 0L,
                            ),
                        )
                    }
                    Optional.ofNullable(cart)
                },
            )!!
        }

    private fun count(table: String, memberId: Long): Long {
        // table is selected only by the two fixed call sites above, never from a caller's input.
        return jdbc.queryForObject(
            "SELECT count(*) FROM pawbridge_store.$table WHERE user_id=:member",
            mapOf("member" to memberId),
            Long::class.javaObjectType,
        )!!
    }

    private fun <T> execute(action: () -> T): T {
        try {
            return read.execute { action() }!!
        } catch (failure: DataAccessException) {
            throw StoreMemberReadUnavailableException(failure)
        } catch (failure: TransactionException) {
            throw StoreMemberReadUnavailableException(failure)
        }
    }

    private fun parameters(memberId: Long, request: StoreMemberReads.Request): MutableMap<String, Any> {
        val result = HashMap<String, Any>()
        result["member"] = memberId
        result["size"] = request.size()
        result["offset"] = request.offset()
        return result
    }

    private data class SortColumn(val sql: String, val text: Boolean)

    private fun orderBy(request: StoreMemberReads.Request, wishlist: Boolean): String {
        val columns = if (wishlist) WISHLIST_SORT else ORDER_SORT
        val result = ArrayList<String>()
        for (order in request.sort()) {
            // The immutable Java map rejects a null property with NullPointerException, as before.
            val column = columns.get(order.property)
                ?: throw IllegalArgumentException("Unsupported Store member sort property: " + order.property)
            val expression = if (order.ignoreCase && column.text) "lower(" + column.sql + ")" else column.sql
            result.add(expression + (if (order.ascending) " ASC" else " DESC"))
        }
        return if (result.isEmpty()) "" else " ORDER BY " + result.joinToString(",")
    }

    private companion object {
        private val WISHLIST_FROM = """
            FROM pawbridge_store.wishlists w
            JOIN pawbridge_store.product_skus s ON s.id=w.sku_id
            JOIN pawbridge_store.products p ON p.id=s.product_id
            LEFT JOIN pawbridge_store.categories c ON c.id=p.category_id
        """.trimIndent() + "\n"

        private val WISHLIST_DETAILS = """
            SELECT w.id AS wishlist_id,w.created_at,s.id AS sku_id,s.sku_code,s.price,
                   p.id AS product_id,p.name AS product_name,p.description,p.image_url,p.status,
                   c.id AS category_id,c.name AS category_name,
                   v.id AS option_value_id,g.name AS group_name,v.name AS value_name
        """.trimIndent() + "\n" + WISHLIST_FROM + """
            LEFT JOIN pawbridge_store.sku_values sv ON sv.product_sku_id=s.id
            LEFT JOIN pawbridge_store.option_values v ON v.id=sv.option_value_id
            LEFT JOIN pawbridge_store.option_groups g ON g.id=v.option_group_id
            WHERE w.user_id=:member AND w.id IN (SELECT id FROM selected)
        """.trimIndent() + "\n"

        private val ORDER_HEADERS = """
            SELECT o.id,o.order_uuid,o.total_amount,o.status,o.delivery_address,o.delivery_status,o.created_at
            FROM pawbridge_store.orders o WHERE o.user_id=:member
        """.trimIndent() + "\n"

        private val ORDER_ITEMS = """
            SELECT i.order_id,i.id AS item_id,i.product_sku_id,i.product_name,i.sku_code,i.price,i.quantity
            FROM pawbridge_store.order_items i JOIN pawbridge_store.orders o ON o.id=i.order_id
            WHERE o.user_id=:member AND o.id IN (:ids)
        """.trimIndent() + "\n"

        private val CART = """
            SELECT c.id AS cart_id,c.user_id,c.created_at,i.id AS item_id,i.product_sku_id,i.quantity,
                   s.id AS live_sku_id,s.sku_code,s.price,p.name AS product_name
            FROM pawbridge_store.carts c LEFT JOIN pawbridge_store.cart_items i ON i.cart_id=c.id
            LEFT JOIN (pawbridge_store.product_skus s JOIN pawbridge_store.products p ON p.id=s.product_id)
                ON s.id=i.product_sku_id
            WHERE c.user_id=:member
        """.trimIndent() + "\n"

        // java.util.Map.ofEntries keeps the original null-key rejection and duplicate-key check.
        private val WISHLIST_SORT: Map<String, SortColumn> = java.util.Map.ofEntries(
            entry("id", "w.id", false),
            entry("userId", "w.user_id", false),
            entry("createdAt", "w.created_at", false),
            entry("updatedAt", "w.updated_at", false),
            entry("sku.id", "s.id", false),
            entry("sku.skuCode", "s.sku_code", true),
            entry("sku.price", "s.price", false),
            entry("sku.stockQuantity", "s.stock_quantity", false),
            // Original PropertyPath aliases for the existing scalar sku.price; retain the caller's Page sort metadata.
            entry("skuPrice", "s.price", false),
            entry("sku_price", "s.price", false),
            entry("sku.createdAt", "s.created_at", false),
            entry("sku.updatedAt", "s.updated_at", false),
            entry("sku.product.id", "p.id", false),
            entry("sku.product.name", "p.name", true),
            entry("sku.product.description", "p.description", true),
            entry("sku.product.imageUrl", "p.image_url", true),
            entry("sku.product.status", "p.status", false),
            entry("sku.product.viewCount", "p.view_count", false),
            entry("sku.product.createdAt", "p.created_at", false),
            entry("sku.product.updatedAt", "p.updated_at", false),
            entry("sku.product.category.id", "c.id", false),
            entry("sku.product.category.name", "c.name", true),
            entry("sku.product.category.description", "c.description", true),
            entry("sku.product.category.createdAt", "c.created_at", false),
            entry("sku.product.category.updatedAt", "c.updated_at", false),
        )

        private val ORDER_SORT: Map<String, SortColumn> = java.util.Map.ofEntries(
            entry("id", "o.id", false),
            entry("userId", "o.user_id", false),
            entry("createdAt", "o.created_at", false),
            entry("updatedAt", "o.updated_at", false),
            entry("orderUuid", "o.order_uuid", true),
            entry("totalAmount", "o.total_amount", false),
            entry("status", "o.status", false),
            entry("deliveryAddress", "o.delivery_address", true),
            entry("deliveryStatus", "o.delivery_status", false),
            entry("receiverName", "o.receiver_name", true),
            entry("receiverPhone", "o.receiver_phone", true),
            entry("deliveryMessage", "o.delivery_message", true),
        )

        private fun entry(property: String, sql: String, text: Boolean): Map.Entry<String, SortColumn> =
            java.util.Map.entry(property, SortColumn(sql, text))
    }
}
