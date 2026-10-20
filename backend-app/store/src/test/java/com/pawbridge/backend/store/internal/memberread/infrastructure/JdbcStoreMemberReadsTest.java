package com.pawbridge.backend.store.internal.memberread.infrastructure;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.pawbridge.backend.store.api.StoreMemberReads;
import com.pawbridge.backend.store.api.StoreMemberReadUnavailableException;
import java.sql.ResultSet;
import java.time.LocalDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcOperations;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionOperations;

class JdbcStoreMemberReadsTest {
    private final NamedParameterJdbcOperations jdbc=mock(NamedParameterJdbcOperations.class);
    private final TransactionOperations tx=mock(TransactionOperations.class);
    private final JdbcStoreMemberReads reads=new JdbcStoreMemberReads(jdbc,tx);
    private final Queue<ResultSet> mapped=new ArrayDeque<>(),extracted=new ArrayDeque<>();
    private final List<String> sql=new ArrayList<>();
    private final List<Map<String,?>> params=new ArrayList<>();
    private boolean active;
    private long count=3L;
    private final LocalDateTime time=LocalDateTime.of(2026,10,20,3,4,5,123456000);

    @BeforeEach void setup() {
        when(tx.execute(any())).thenAnswer(call -> {
            active=true;
            try { return call.<TransactionCallback<?>>getArgument(0).doInTransaction(new SimpleTransactionStatus()); }
            finally { active=false; }
        });
        when(jdbc.query(anyString(),anyMap(),any(RowMapper.class))).thenAnswer(call -> {
            capture(call.getArgument(0),call.getArgument(1));
            ResultSet rs=mapped.remove();RowMapper<?> mapper=call.getArgument(2);List<Object> results=new ArrayList<>();int n=0;
            while(rs.next()) results.add(mapper.mapRow(rs,n++));return results;
        });
        when(jdbc.query(anyString(),anyMap(),any(ResultSetExtractor.class))).thenAnswer(call -> {
            capture(call.getArgument(0),call.getArgument(1));return call.<ResultSetExtractor<?>>getArgument(2).extractData(extracted.remove());
        });
        when(jdbc.queryForObject(anyString(),anyMap(),eq(Long.class))).thenAnswer(call -> { capture(call.getArgument(0),call.getArgument(1));return count; });
    }

    @Test void given__wishlist_page_and_options__when__reading__then__preserve_member_scope_parent_order_all_statuses_and_source_fields() throws Exception {
        extracted.add(rows(wish(11L,21L,"HIDDEN",null,null,null,null,null),
                wish(12L,22L,"DELETED",8L,"사료",3L,"색상","파랑"),wish(12L,22L,"DELETED",8L,"사료",4L,"크기","대")));
        var result=reads.wishlists(7,request(0,2));
        assertEquals(List.of(
                new StoreMemberReads.Wishlist(11L,21L,"SKU21",101L,"상품21",null,null,"HIDDEN",9007199254740993L,Map.of(),null,null,time),
                new StoreMemberReads.Wishlist(12L,22L,"SKU22",102L,"상품22",null,null,"DELETED",9007199254740993L,Map.of("색상","파랑","크기","대"),8L,"사료",time)),result.content());
        assertEquals(3L,result.totalElements());assertEquals(0,result.number());assertEquals(2,result.size());
        assertTrue(sql.getFirst().contains("w.user_id=:member ORDER BY w.created_at DESC LIMIT :size OFFSET :offset"));
        assertTrue(sql.getFirst().startsWith("WITH selected AS (SELECT w.id"));
        assertTrue(sql.getFirst().contains("w.user_id=:member AND w.id IN (SELECT id FROM selected)"));
        assertTrue(sql.getFirst().indexOf("LIMIT :size OFFSET :offset")<sql.getFirst().indexOf("LEFT JOIN pawbridge_store.sku_values"));
        assertFalse(sql.getFirst().contains("p.status="));assertEquals(2,sql.size());
        assertMember(7);assertFalse(active);verify(tx,times(1)).execute(any());
    }

    @Test void given__no_first_page_wishlist__when__reading__then__empty_page_without_detail_or_count() throws Exception {
        extracted.add(rows());
        assertEquals(new StoreMemberReads.Page<>(List.of(),0,20,0L,request(0,20).sort()),reads.wishlists(19,request(0,20)));
        assertEquals(1,sql.size());assertMember(19);
    }

    @Test void given__later_empty_wishlist_page__when__reading__then__retain_total_count_and_unsorted_request() throws Exception {
        count=2;extracted.add(rows());var request=new StoreMemberReads.Request(3,2,List.of());
        var result=reads.wishlists(7,request);
        assertEquals(2,result.totalElements());assertEquals(List.of(),result.sort());assertFalse(sql.getFirst().contains("ORDER BY"));
        assertEquals(6L,params.getFirst().get("offset"));assertEquals(2,sql.size());
    }

    @Test void given__partial_later_wishlist_page__when__reading__then__derive_total_without_count() throws Exception {
        extracted.add(rows(wish(11L,21L,"ACTIVE",null,null,null,null,null)));
        assertEquals(5L,reads.wishlists(7,request(2,2)).totalElements());assertEquals(1,sql.size());
    }

    @Test void given__two_order_headers_and_out_of_order_item_rows__when__reading__then__preserve_order_snapshots_and_page_boundaries() throws Exception {
        mapped.add(rows(purchase(9,"CANCELLED"),purchase(10,"FAILED")));
        extracted.add(rows(map("order_id",10L,"item_id",31L,"product_sku_id",71L,"product_name","구매시상품B","sku_code","OLD-B","price",4L,"quantity",5),
                map("order_id",9L,"item_id",32L,"product_sku_id",72L,"product_name","구매시상품A","sku_code","OLD-A","price",9007199254740993L,"quantity",1)));
        var result=reads.orders(7,request(0,2));
        assertEquals(List.of(new StoreMemberReads.Purchase(9L,"uuid9",9007199254740993L,"CANCELLED","주소","READY",List.of(
                new StoreMemberReads.OrderItem(32L,72L,"구매시상품A","OLD-A",9007199254740993L,1)),time),
                new StoreMemberReads.Purchase(10L,"uuid10",9007199254740993L,"FAILED","주소","READY",List.of(
                new StoreMemberReads.OrderItem(31L,71L,"구매시상품B","OLD-B",4L,5)),time)),result.content());
        assertEquals(3,result.totalElements());assertTrue(sql.getFirst().contains("LIMIT :size OFFSET :offset"));
        assertTrue(sql.get(1).contains("o.user_id=:member AND o.id IN (:ids)"));
        assertFalse(sql.get(1).contains("product_skus"));assertFalse(sql.getFirst().contains("o.status="));assertMember(7);
    }

    @Test void given__persisted_order_without_items__when__reading__then__preserve_order_with_empty_items_and_nullable_time() throws Exception {
        var header=purchase(9,"PENDING");header.put("created_at",null);mapped.add(rows(header));extracted.add(rows());
        assertEquals(new StoreMemberReads.Purchase(9L,"uuid9",9007199254740993L,"PENDING","주소","READY",List.of(),null),reads.orders(7,request(0,20)).content().getFirst());
        assertEquals(2,sql.size());
    }

    @Test void given__absent_order_page__when__reading__then__empty_success_and_no_item_query() throws Exception {
        mapped.add(rows());assertEquals(List.of(),reads.orders(7,request(0,20)).content());assertEquals(1,sql.size());
    }

    @Test void given__no_persisted_cart__when__reading__then__optional_empty_without_creation() throws Exception {
        extracted.add(rows());assertTrue(reads.cart(7).isEmpty());assertEquals(1,sql.size());assertMember(7);
    }

    @Test void given__empty_persisted_cart__when__reading__then__present_cart_with_empty_items() throws Exception {
        extracted.add(rows(map("cart_id",8L,"user_id",7L,"created_at",null,"item_id",null)));
        assertEquals(new StoreMemberReads.Cart(8L,7L,List.of(),null),reads.cart(7).orElseThrow());assertMember(7);
    }

    @Test void given__live_and_missing_cart_sku__when__reading__then__use_product_sku_id_and_preserve_missing_item_and_original_money_math() throws Exception {
        extracted.add(rows(map("cart_id",8L,"user_id",7L,"created_at",time,"item_id",9L,"product_sku_id",90L,"quantity",2,
                "live_sku_id",90L,"sku_code","LIVE","price",Long.MAX_VALUE,"product_name","현재상품"),
                map("cart_id",8L,"user_id",7L,"created_at",time,"item_id",10L,"product_sku_id",91L,"quantity",3,
                        "live_sku_id",null,"sku_code","MUST-NOT-LEAK","price",99L,"product_name","MUST-NOT-LEAK")));
        assertEquals(new StoreMemberReads.Cart(8L,7L,List.of(
                new StoreMemberReads.CartItem(9L,90L,"현재상품","LIVE",Long.MAX_VALUE,2,-2L),
                new StoreMemberReads.CartItem(10L,91L,null,null,0L,3,0L)),time),reads.cart(7).orElseThrow());
        assertTrue(sql.getFirst().contains("ON s.id=i.product_sku_id"));assertFalse(sql.getFirst().contains("i.sku_id"));assertMember(7);assertFalse(active);
    }

    @ParameterizedTest @MethodSource("sorts")
    void given__supported_scalar_sort__when__reading__then__use_fixed_column_direction_and_jpa_string_ignore_case(boolean wishlist,List<StoreMemberReads.Order> order,String expected) throws Exception {
        if(wishlist) extracted.add(rows());else mapped.add(rows());var query=new StoreMemberReads.Request(0,20,order);
        if(wishlist) reads.wishlists(7,query);else reads.orders(7,query);
        assertTrue(sql.getFirst().contains(expected+" LIMIT"));
    }
    static Stream<Arguments> sorts() { return Stream.of(
            Arguments.of(true,List.of(new StoreMemberReads.Order("sku.product.name",true,true)),"ORDER BY lower(p.name) ASC"),
            Arguments.of(true,List.of(new StoreMemberReads.Order("sku.price",false,true)),"ORDER BY s.price DESC"),
            Arguments.of(true,List.of(new StoreMemberReads.Order("sku.product.category.name",false,false)),"ORDER BY c.name DESC"),
            Arguments.of(false,List.of(new StoreMemberReads.Order("receiverName",true,true)),"ORDER BY lower(o.receiver_name) ASC"),
            Arguments.of(false,List.of(new StoreMemberReads.Order("createdAt",false,false),new StoreMemberReads.Order("id",true,false)),"ORDER BY o.created_at DESC,o.id ASC")); }

    @ParameterizedTest @MethodSource("badSorts")
    void given__unknown_or_injected_sort__when__reading__then__reject_before_transaction_or_database(boolean wishlist,String property) {
        var request=new StoreMemberReads.Request(0,20,List.of(new StoreMemberReads.Order(property,true,false)));
        assertThrows(IllegalArgumentException.class,()->{ if(wishlist) reads.wishlists(7,request);else reads.orders(7,request); });
        verifyNoInteractions(tx,jdbc);
    }
    static Stream<Arguments> badSorts() { return Stream.of(Arguments.of(true,"id; DELETE FROM wishlists"),Arguments.of(false,"unknown")); }

    @ParameterizedTest @MethodSource("failures")
    void given__database_or_transaction_failure__when__reading__then__expose_jdk_api_failure_and_original_cause(String operation,boolean transaction) {
        RuntimeException failure=transaction?new CannotCreateTransactionException("synthetic private details"):new DataAccessResourceFailureException("synthetic private details");
        if(transaction) doThrow(failure).when(tx).execute(any());
        else {
            doThrow(failure).when(jdbc).query(anyString(),anyMap(),any(RowMapper.class));
            doThrow(failure).when(jdbc).query(anyString(),anyMap(),any(ResultSetExtractor.class));
        }
        var unavailable=assertThrows(StoreMemberReadUnavailableException.class,()->{
            if(operation.equals("wishlist")) reads.wishlists(7,request(0,20));
            else if(operation.equals("orders")) reads.orders(7,request(0,20));else reads.cart(7);
        });
        assertSame(failure,unavailable.getCause());assertEquals("Store member information could not be read",unavailable.getMessage());assertFalse(active);
    }
    static Stream<Arguments> failures() { return Stream.of("wishlist","orders","cart").flatMap(op->Stream.of(Arguments.of(op,false),Arguments.of(op,true))); }

    private void capture(String query,Map<String,?> bound) { assertTrue(active);sql.add(query);params.add(new HashMap<>(bound)); }
    private void assertMember(long id) { for(var values:params) assertEquals(id,values.get("member")); }
    private static StoreMemberReads.Request request(int page,int size) { return new StoreMemberReads.Request(page,size,List.of(new StoreMemberReads.Order("createdAt",false,false))); }
    private Map<String,Object> purchase(long id,String status) { return map("id",id,"order_uuid","uuid"+id,"total_amount",9007199254740993L,
            "status",status,"delivery_address","주소","delivery_status","READY","created_at",time); }
    private Map<String,Object> wish(long id,long sku,String status,Long category,String categoryName,Long option,String group,String value) {
        return map("wishlist_id",id,"sku_id",sku,"sku_code","SKU"+sku,"product_id",sku+80,"product_name","상품"+sku,
                "description",null,"image_url",null,"status",status,"price",9007199254740993L,"category_id",category,"category_name",categoryName,
                "created_at",time,"option_value_id",option,"group_name",group,"value_name",value);
    }
    private static Map<String,Object> map(Object... cells) { Map<String,Object> row=new HashMap<>();for(int i=0;i<cells.length;i+=2) row.put((String)cells[i],cells[i+1]);return row; }
    @SafeVarargs private static ResultSet rows(Map<String,Object>... rows) {
        AtomicInteger i=new AtomicInteger(-1);
        return mock(ResultSet.class,call->{
            String method=call.getMethod().getName();if(method.equals("next")) return i.incrementAndGet()<rows.length;
            if(method.startsWith("get") && call.getArgument(0) instanceof String column) {
                Object value=rows[i.get()].get(column);
                return switch(method) { case "getLong"->value==null?0L:((Number)value).longValue();case "getInt"->value==null?0:((Number)value).intValue();default->value; };
            }
            return RETURNS_DEFAULTS.answer(call);
        });
    }
}
