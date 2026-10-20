package com.pawbridge.backend.store.internal.memberread.infrastructure;

import com.pawbridge.backend.store.api.StoreMemberReads;
import com.pawbridge.backend.store.api.StoreMemberReadUnavailableException;
import com.pawbridge.backend.store.internal.memberread.application.MemberReadPages;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcOperations;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionOperations;

/** Three persisted MyPage reads only. No Redis cart, synchronization, writes or external calls. */
public final class JdbcStoreMemberReads implements StoreMemberReads {
    private static final String WISHLIST_FROM="""
            FROM pawbridge_store.wishlists w
            JOIN pawbridge_store.product_skus s ON s.id=w.sku_id
            JOIN pawbridge_store.products p ON p.id=s.product_id
            LEFT JOIN pawbridge_store.categories c ON c.id=p.category_id
            """;
    private static final String WISHLIST_DETAILS="""
            SELECT w.id AS wishlist_id,w.created_at,s.id AS sku_id,s.sku_code,s.price,
                   p.id AS product_id,p.name AS product_name,p.description,p.image_url,p.status,
                   c.id AS category_id,c.name AS category_name,
                   v.id AS option_value_id,g.name AS group_name,v.name AS value_name
            """+WISHLIST_FROM+"""
            LEFT JOIN pawbridge_store.sku_values sv ON sv.product_sku_id=s.id
            LEFT JOIN pawbridge_store.option_values v ON v.id=sv.option_value_id
            LEFT JOIN pawbridge_store.option_groups g ON g.id=v.option_group_id
            WHERE w.user_id=:member AND w.id IN (SELECT id FROM selected)
            """;
    private static final String ORDER_HEADERS="""
            SELECT o.id,o.order_uuid,o.total_amount,o.status,o.delivery_address,o.delivery_status,o.created_at
            FROM pawbridge_store.orders o WHERE o.user_id=:member
            """;
    private static final String ORDER_ITEMS="""
            SELECT i.order_id,i.id AS item_id,i.product_sku_id,i.product_name,i.sku_code,i.price,i.quantity
            FROM pawbridge_store.order_items i JOIN pawbridge_store.orders o ON o.id=i.order_id
            WHERE o.user_id=:member AND o.id IN (:ids)
            """;
    private static final String CART="""
            SELECT c.id AS cart_id,c.user_id,c.created_at,i.id AS item_id,i.product_sku_id,i.quantity,
                   s.id AS live_sku_id,s.sku_code,s.price,p.name AS product_name
            FROM pawbridge_store.carts c LEFT JOIN pawbridge_store.cart_items i ON i.cart_id=c.id
            LEFT JOIN (pawbridge_store.product_skus s JOIN pawbridge_store.products p ON p.id=s.product_id)
                ON s.id=i.product_sku_id
            WHERE c.user_id=:member
            """;
    private final NamedParameterJdbcOperations jdbc;
    private final TransactionOperations read;

    public JdbcStoreMemberReads(NamedParameterJdbcOperations jdbc,TransactionOperations read) {
        this.jdbc=jdbc;this.read=read;
    }

    @Override public Page<Wishlist> wishlists(long memberId,Request request) {
        String order=orderBy(request,true);
        return execute(() -> {
            Map<String,Object> params=parameters(memberId,request);
            // Select page parents before expanding options, in the same statement snapshot as the detail rows.
            String sql="WITH selected AS (SELECT w.id "+WISHLIST_FROM+" WHERE w.user_id=:member"+order
                    +" LIMIT :size OFFSET :offset) "+WISHLIST_DETAILS+order;
            List<Wishlist> content=jdbc.query(sql,params,rs -> {
                    Map<Long,Wishlist> result=new LinkedHashMap<>();
                    while(rs.next()) {
                        long id=rs.getLong("wishlist_id");
                        Wishlist item=result.get(id);
                        if(item==null) {
                            item=new Wishlist(id,rs.getLong("sku_id"),rs.getString("sku_code"),rs.getLong("product_id"),
                                    rs.getString("product_name"),rs.getString("description"),rs.getString("image_url"),
                                    rs.getString("status"),rs.getLong("price"),new HashMap<>(),rs.getObject("category_id",Long.class),
                                    rs.getString("category_name"),rs.getObject("created_at",LocalDateTime.class));
                            result.put(id,item);
                        }
                        if(rs.getObject("option_value_id")!=null) item.options().put(rs.getString("group_name"),rs.getString("value_name"));
                    }
                    return new ArrayList<>(result.values());
                });
            return MemberReadPages.page(content,request,()->count("wishlists",memberId));
        });
    }

    @Override public Page<Purchase> orders(long memberId,Request request) {
        String order=orderBy(request,false);
        return execute(() -> {
            Map<String,Object> params=parameters(memberId,request);
            List<Purchase> headers=jdbc.query(ORDER_HEADERS+order+" LIMIT :size OFFSET :offset",params,(rs,n) ->
                    new Purchase(rs.getLong("id"),rs.getString("order_uuid"),rs.getLong("total_amount"),rs.getString("status"),
                            rs.getString("delivery_address"),rs.getString("delivery_status"),new ArrayList<>(),rs.getObject("created_at",LocalDateTime.class)));
            if(!headers.isEmpty()) {
                params.put("ids",headers.stream().map(Purchase::orderId).toList());
                Map<Long,List<OrderItem>> items=jdbc.query(ORDER_ITEMS,params,rs -> {
                    Map<Long,List<OrderItem>> result=new HashMap<>();
                    while(rs.next()) result.computeIfAbsent(rs.getLong("order_id"),ignored->new ArrayList<>()).add(
                            new OrderItem(rs.getLong("item_id"),rs.getLong("product_sku_id"),rs.getString("product_name"),
                                    rs.getString("sku_code"),rs.getLong("price"),rs.getInt("quantity")));
                    return result;
                });
                for(Purchase header:headers) header.items().addAll(items.getOrDefault(header.orderId(),List.of()));
            }
            return MemberReadPages.page(headers,request,()->count("orders",memberId));
        });
    }

    @Override public Optional<Cart> cart(long memberId) {
        return execute(() -> jdbc.query(CART,Map.of("member",memberId),rs -> {
            Cart cart=null;
            while(rs.next()) {
                long id=rs.getLong("cart_id");
                if(cart==null) cart=new Cart(id,rs.getLong("user_id"),new ArrayList<>(),rs.getObject("created_at",LocalDateTime.class));
                else if(cart.cartId()!=id) throw new IllegalStateException("Multiple carts for one member");
                if(rs.getObject("item_id")==null) continue;
                boolean exists=rs.getObject("live_sku_id")!=null;
                long price=exists?rs.getLong("price"):0L;
                int quantity=rs.getInt("quantity");
                cart.items().add(new CartItem(rs.getLong("item_id"),rs.getLong("product_sku_id"),
                        exists?rs.getString("product_name"):null,exists?rs.getString("sku_code"):null,
                        price,quantity,exists?price*quantity:0L));
            }
            return Optional.ofNullable(cart);
        }));
    }

    private long count(String table,long memberId) {
        // table is selected only by the two fixed call sites above, never from a caller's input.
        return jdbc.queryForObject("SELECT count(*) FROM pawbridge_store."+table+" WHERE user_id=:member",Map.of("member",memberId),Long.class);
    }

    private <T> T execute(Supplier<T> action) {
        try { return read.execute(status->action.get()); }
        catch(DataAccessException | TransactionException failure) { throw new StoreMemberReadUnavailableException(failure); }
    }

    private static Map<String,Object> parameters(long memberId,Request request) {
        Map<String,Object> result=new HashMap<>();result.put("member",memberId);result.put("size",request.size());result.put("offset",request.offset());return result;
    }

    private record SortColumn(String sql,boolean text) { }
    private static String orderBy(Request request,boolean wishlist) {
        Map<String,SortColumn> columns=wishlist?WISHLIST_SORT:ORDER_SORT;
        List<String> result=new ArrayList<>();
        for(Order order:request.sort()) {
            SortColumn column=columns.get(order.property());
            if(column==null) throw new IllegalArgumentException("Unsupported Store member sort property: "+order.property());
            String expression=order.ignoreCase()&&column.text()?"lower("+column.sql()+")":column.sql();
            result.add(expression+(order.ascending()?" ASC":" DESC"));
        }
        return result.isEmpty()?"":" ORDER BY "+String.join(",",result);
    }

    private static final Map<String,SortColumn> WISHLIST_SORT=Map.ofEntries(
            entry("id","w.id",false),entry("userId","w.user_id",false),entry("createdAt","w.created_at",false),entry("updatedAt","w.updated_at",false),
            entry("sku.id","s.id",false),entry("sku.skuCode","s.sku_code",true),entry("sku.price","s.price",false),entry("sku.stockQuantity","s.stock_quantity",false),
            // Original PropertyPath aliases for the existing scalar sku.price; retain the caller's Page sort metadata.
            entry("skuPrice","s.price",false),entry("sku_price","s.price",false),
            entry("sku.createdAt","s.created_at",false),entry("sku.updatedAt","s.updated_at",false),
            entry("sku.product.id","p.id",false),entry("sku.product.name","p.name",true),entry("sku.product.description","p.description",true),
            entry("sku.product.imageUrl","p.image_url",true),entry("sku.product.status","p.status",false),entry("sku.product.viewCount","p.view_count",false),
            entry("sku.product.createdAt","p.created_at",false),entry("sku.product.updatedAt","p.updated_at",false),
            entry("sku.product.category.id","c.id",false),entry("sku.product.category.name","c.name",true),
            entry("sku.product.category.description","c.description",true),entry("sku.product.category.createdAt","c.created_at",false),entry("sku.product.category.updatedAt","c.updated_at",false));
    private static final Map<String,SortColumn> ORDER_SORT=Map.ofEntries(
            entry("id","o.id",false),entry("userId","o.user_id",false),entry("createdAt","o.created_at",false),entry("updatedAt","o.updated_at",false),
            entry("orderUuid","o.order_uuid",true),entry("totalAmount","o.total_amount",false),entry("status","o.status",false),
            entry("deliveryAddress","o.delivery_address",true),entry("deliveryStatus","o.delivery_status",false),
            entry("receiverName","o.receiver_name",true),entry("receiverPhone","o.receiver_phone",true),entry("deliveryMessage","o.delivery_message",true));
    private static Map.Entry<String,SortColumn> entry(String property,String sql,boolean text) { return Map.entry(property,new SortColumn(sql,text)); }
}
