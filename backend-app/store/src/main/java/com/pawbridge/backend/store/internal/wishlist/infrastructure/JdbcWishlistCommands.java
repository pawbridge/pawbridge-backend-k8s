package com.pawbridge.backend.store.internal.wishlist.infrastructure;

import com.pawbridge.backend.store.internal.wishlist.application.WishlistCommands;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcOperations;
import org.springframework.transaction.support.TransactionOperations;

/** Root supplies a Store writer transaction. This class is never self-registered or run by preparation tests. */
public final class JdbcWishlistCommands implements WishlistCommands {
    private static final String EXISTS="""
            SELECT EXISTS(SELECT 1 FROM pawbridge_store.wishlists WHERE user_id=:member AND sku_id=:sku)
            """;
    private static final String SKU="""
            SELECT s.id AS sku_id,s.sku_code,s.price,p.id AS product_id,p.name AS product_name,p.image_url,
                   v.id AS option_value_id,g.name AS group_name,v.name AS value_name
            FROM pawbridge_store.product_skus s JOIN pawbridge_store.products p ON p.id=s.product_id
            LEFT JOIN pawbridge_store.sku_values sv ON sv.product_sku_id=s.id
            LEFT JOIN pawbridge_store.option_values v ON v.id=sv.option_value_id
            LEFT JOIN pawbridge_store.option_groups g ON g.id=v.option_group_id
            WHERE s.id=:sku
            """;
    private static final String INSERT="""
            INSERT INTO pawbridge_store.wishlists(user_id,sku_id,created_at,updated_at)
            VALUES (:member,:sku,:created,:created) RETURNING id
            """;
    private final NamedParameterJdbcOperations jdbc;
    private final TransactionOperations write;
    private final Clock clock;

    public JdbcWishlistCommands(NamedParameterJdbcOperations jdbc,TransactionOperations write,Clock clock) {
        this.jdbc=jdbc;this.write=write;this.clock=clock;
    }

    @Override public Added add(long currentMemberId,Long skuId) {
        return write.execute(status -> {
            Map<String,Object> params=new HashMap<>();params.put("member",currentMemberId);params.put("sku",skuId);
            // Original precedence: duplicate check first, missing/null SKU second; no product-status/stock restriction.
            if(Boolean.TRUE.equals(jdbc.queryForObject(EXISTS,params,Boolean.class)))
                throw new IllegalStateException("이미 찜한 상품입니다.");
            if(skuId==null) throw new IllegalArgumentException("The given id must not be null");
            Added sku=jdbc.query(SKU,params,rs -> {
                Added result=null;
                while(rs.next()) {
                    if(result==null) result=new Added(null,currentMemberId,rs.getLong("sku_id"),rs.getString("sku_code"),
                            rs.getLong("product_id"),rs.getString("product_name"),rs.getString("image_url"),
                            rs.getLong("price"),new HashMap<>(),null);
                    if(rs.getObject("option_value_id")!=null) result.options().put(rs.getString("group_name"),rs.getString("value_name"));
                }
                return result;
            });
            if(sku==null) throw new IllegalArgumentException("SKU를 찾을 수 없습니다: "+skuId);
            // Match JPA auditing's JVM-local time and updated-on-create. Preserve the application value in the DTO.
            LocalDateTime created=LocalDateTime.now(clock);
            params.put("created",created);
            Long id=jdbc.queryForObject(INSERT,params,Long.class);
            if(id==null) throw new IllegalStateException("Wishlist insert did not return an identity");
            return new Added(id,currentMemberId,sku.skuId(),sku.skuCode(),sku.productId(),sku.productName(),
                    sku.productImageUrl(),sku.price(),sku.options(),created);
        });
    }

    @Override public void removeByMemberAndSku(long currentMemberId,long skuId) {
        write.executeWithoutResult(status -> {
            Map<String,Object> params=new HashMap<>(Map.of("member",currentMemberId,"sku",skuId));
            List<Long> ids=jdbc.query("SELECT id FROM pawbridge_store.wishlists WHERE user_id=:member AND sku_id=:sku",params,(rs,n)->rs.getLong("id"));
            if(ids.isEmpty()) throw new IllegalArgumentException("찜을 찾을 수 없습니다.");
            // Original Optional query fails on pre-existing duplicate pairs; never silently delete all duplicates.
            if(ids.size()!=1) throw new IllegalStateException("Multiple wishlist rows for the member and SKU");
            params.put("id",ids.getFirst());
            int deleted=jdbc.update("DELETE FROM pawbridge_store.wishlists WHERE id=:id AND user_id=:member AND sku_id=:sku",params);
            if(deleted!=1) throw new IllegalStateException("Wishlist changed during deletion");
        });
    }

    @Override public void removeById(long currentMemberId,long wishlistId) {
        write.executeWithoutResult(status -> {
            // One atomic owner check. Missing, wrong-owner and repeated/concurrent deletes are the same no-op.
            jdbc.update("DELETE FROM pawbridge_store.wishlists WHERE id=:id AND user_id=:member",
                    Map.of("member",currentMemberId,"id",wishlistId));
        });
    }
}
