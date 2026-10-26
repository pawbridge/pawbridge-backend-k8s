package com.pawbridge.backend.store.internal.wishlist.infrastructure

import com.pawbridge.backend.store.internal.wishlist.application.WishlistCommands
import org.springframework.jdbc.core.ResultSetExtractor
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcOperations
import org.springframework.transaction.support.TransactionOperations
import java.time.Clock
import java.time.LocalDateTime

/** Root supplies a Store writer transaction. This class is never self-registered or run by preparation tests. */
class JdbcWishlistCommands(
    private val jdbc: NamedParameterJdbcOperations,
    private val write: TransactionOperations,
    private val clock: Clock,
) : WishlistCommands {

    override fun add(currentMemberId: Long, skuId: Long?): WishlistCommands.Added =
        write.execute {
            val params = HashMap<String, Any?>()
            params["member"] = currentMemberId
            params["sku"] = skuId
            // Original precedence: duplicate check first, missing/null SKU second; no product-status/stock restriction.
            if (jdbc.queryForObject(EXISTS, params, Boolean::class.javaObjectType) == true) {
                throw IllegalStateException("이미 찜한 상품입니다.")
            }
            if (skuId == null) {
                throw IllegalArgumentException("The given id must not be null")
            }
            val sku: WishlistCommands.Added? = jdbc.query(
                SKU,
                params,
                ResultSetExtractor<WishlistCommands.Added?> { rs ->
                    var result: WishlistCommands.Added? = null
                    while (rs.next()) {
                        if (result == null) {
                            result = WishlistCommands.Added(
                                null,
                                currentMemberId,
                                rs.getLong("sku_id"),
                                rs.getString("sku_code"),
                                rs.getLong("product_id"),
                                rs.getString("product_name"),
                                rs.getString("image_url"),
                                rs.getLong("price"),
                                HashMap<String, String>(),
                                null,
                            )
                        }
                        if (rs.getObject("option_value_id") != null) {
                            result.options!!.put(rs.getString("group_name"), rs.getString("value_name"))
                        }
                    }
                    result
                },
            )
            if (sku == null) {
                throw IllegalArgumentException("SKU를 찾을 수 없습니다: $skuId")
            }
            // Match JPA auditing's JVM-local time and updated-on-create. Preserve the application value in the DTO.
            val created = LocalDateTime.now(clock)
            params["created"] = created
            val id = jdbc.queryForObject(INSERT, params, Long::class.javaObjectType)
            if (id == null) {
                throw IllegalStateException("Wishlist insert did not return an identity")
            }
            WishlistCommands.Added(
                id,
                currentMemberId,
                sku.skuId,
                sku.skuCode,
                sku.productId,
                sku.productName,
                sku.productImageUrl,
                sku.price,
                sku.options,
                created,
            )
        }!!

    override fun removeByMemberAndSku(currentMemberId: Long, skuId: Long) {
        write.executeWithoutResult {
            val params = HashMap<String, Any>(mapOf("member" to currentMemberId, "sku" to skuId))
            val ids: List<Long> = jdbc.query(
                "SELECT id FROM pawbridge_store.wishlists WHERE user_id=:member AND sku_id=:sku",
                params,
                RowMapper { rs, _ -> rs.getLong("id") },
            )
            if (ids.isEmpty()) {
                throw IllegalArgumentException("찜을 찾을 수 없습니다.")
            }
            // Original Optional query fails on pre-existing duplicate pairs; never silently delete all duplicates.
            if (ids.size != 1) {
                throw IllegalStateException("Multiple wishlist rows for the member and SKU")
            }
            params["id"] = ids.first()
            val deleted = jdbc.update(
                "DELETE FROM pawbridge_store.wishlists WHERE id=:id AND user_id=:member AND sku_id=:sku",
                params,
            )
            if (deleted != 1) {
                throw IllegalStateException("Wishlist changed during deletion")
            }
        }
    }

    override fun removeById(currentMemberId: Long, wishlistId: Long) {
        write.executeWithoutResult {
            // One atomic owner check. Missing, wrong-owner and repeated/concurrent deletes are the same no-op.
            jdbc.update(
                "DELETE FROM pawbridge_store.wishlists WHERE id=:id AND user_id=:member",
                mapOf("member" to currentMemberId, "id" to wishlistId),
            )
        }
    }

    private companion object {
        private val EXISTS = """
            SELECT EXISTS(SELECT 1 FROM pawbridge_store.wishlists WHERE user_id=:member AND sku_id=:sku)
        """.trimIndent() + "\n"

        private val SKU = """
            SELECT s.id AS sku_id,s.sku_code,s.price,p.id AS product_id,p.name AS product_name,p.image_url,
                   v.id AS option_value_id,g.name AS group_name,v.name AS value_name
            FROM pawbridge_store.product_skus s JOIN pawbridge_store.products p ON p.id=s.product_id
            LEFT JOIN pawbridge_store.sku_values sv ON sv.product_sku_id=s.id
            LEFT JOIN pawbridge_store.option_values v ON v.id=sv.option_value_id
            LEFT JOIN pawbridge_store.option_groups g ON g.id=v.option_group_id
            WHERE s.id=:sku
        """.trimIndent() + "\n"

        private val INSERT = """
            INSERT INTO pawbridge_store.wishlists(user_id,sku_id,created_at,updated_at)
            VALUES (:member,:sku,:created,:created) RETURNING id
        """.trimIndent() + "\n"
    }
}
