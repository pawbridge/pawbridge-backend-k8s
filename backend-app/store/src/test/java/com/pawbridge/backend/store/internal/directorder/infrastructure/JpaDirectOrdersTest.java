package com.pawbridge.backend.store.internal.directorder.infrastructure;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.pawbridge.backend.store.api.StoreOrderActors;
import com.pawbridge.backend.store.api.StoreMemberReads;
import com.pawbridge.backend.store.internal.directorder.application.DirectOrders;
import com.pawbridge.backend.store.internal.directorder.application.OrderActorRejectedException;
import com.pawbridge.backend.store.internal.memberread.infrastructure.jpa.model.*;
import java.util.*;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

class JpaDirectOrdersTest {
    private DirectOrderFixture f;
    @BeforeEach void prepare(){f=new DirectOrderFixture();}
    @AfterEach void close(){f.close();}

    @Test void given__active_member_and_locked_stock__when__buying_then_reading_own_order__then__preserve_snapshot_and_full_response_with_transactional_followups() throws Exception {
        var created=f.service.create(7,f.command(2));
        assertEquals(new DirectOrders.OrderDetails(33L,created.orderUuid(),7L,200L,"PENDING","READY","fixture receiver","fixture phone","fixture address","fixture message",f.time,
                List.of(new DirectOrders.Item("fixture product","CURRENT-80",100L,2))),created);
        UUID.fromString(created.orderUuid());assertEquals(3,f.target.getStockQuantity());assertEquals(8,f.sibling.getStockQuantity());
        assertEquals(List.of("begin:writer","actor:write","reference","product-lock","all-sku-lock","search","outbox:80","outbox:81","sku-read","order-save","commit:writer"),f.events);
        assertEquals(2,f.snapshots.size());
        var first=f.json.readTree(f.snapshots.get(0).getPayload());var second=f.json.readTree(f.snapshots.get(1).getPayload());
        assertEquals(14,first.size());assertEquals(14,second.size());
        for(var payload:List.of(first,second)) {
            assertEquals(90L,payload.get("productId").longValue());assertTrue(payload.get("categoryId").isNull());
            assertEquals("fixture product",payload.get("productName").asText());assertEquals("ACTIVE",payload.get("status").asText());
            assertTrue(payload.get("imageUrl").isNull());assertEquals(f.time.toString(),payload.get("createdAt").asText());
            assertEquals("",payload.get("optionName").asText());
        }
        assertEquals(80L,first.get("skuId").longValue());assertEquals("CURRENT-80",first.get("skuCode").asText());assertEquals(100L,first.get("price").longValue());
        assertEquals(81L,second.get("skuId").longValue());assertEquals("CURRENT-81",second.get("skuCode").asText());assertEquals(50L,second.get("price").longValue());
        assertEquals(3,first.get("stockQuantity").intValue());assertEquals(8,second.get("stockQuantity").intValue());
        assertEquals(11,first.get("totalStockQuantity").intValue());assertEquals(11,second.get("totalStockQuantity").intValue());
        assertFalse(first.get("isPrimarySku").booleanValue());assertTrue(second.get("isPrimarySku").booleanValue());
        assertEquals(first.get("updatedAt"),second.get("updatedAt"));assertEquals(f.time.toString(),first.get("updatedAt").asText());
        for(var row:f.snapshots){assertEquals("product-sku",row.getAggregateType());assertEquals("SKU_UPDATED",row.getEventType());}
        assertEquals(81L,f.searchParameters.getFirst().get("skuId"));assertEquals("nori-9.12.3-v1",f.searchParameters.getFirst().get("version"));
        f.product.updateName("later product name");f.target.updatePrice(999L);
        assertEquals(created,f.service.get(7,33));
        assertEquals(List.of("begin:reader","actor:read","order-read","commit:reader"),f.events.subList(11,15));
        var stored=f.orders.get(33L);assertSame(stored,stored.getOrderItems().getFirst().getOrder());
    }

    static Stream<Arguments> actors(){return Stream.of("null","mismatch","inactive","deleted").flatMap(state->Stream.of("create","get").map(operation->Arguments.of(state,operation)));}
    @ParameterizedTest @MethodSource("actors")
    void given__ineligible_current_actor__when__accessing_order_flow__then__reject_before_store_rows_or_followups(String state,String operation) {
        f.actor=switch(state){case "null"->null;case "mismatch"->new StoreOrderActors.Actor(8,true,false);case "inactive"->new StoreOrderActors.Actor(7,false,false);default->new StoreOrderActors.Actor(7,true,true);};
        assertThrows(OrderActorRejectedException.class,()->{if(operation.equals("create"))f.service.create(7,f.command(1));else f.service.get(7,33);});
        verifyNoInteractions(f.products,f.skus,f.writerOrders,f.readerOrders,f.jdbc,f.outbox);
        if(operation.equals("create")){verify(f.actors).currentForWrite(7);verify(f.actors,never()).currentForRead(anyLong());}
        else {verify(f.actors).currentForRead(7);verify(f.actors,never()).currentForWrite(anyLong());}
        assertEquals("rollback:"+(operation.equals("create")?"writer":"reader"),f.events.getLast());
    }

    @ParameterizedTest @ValueSource(ints={0,-1})
    void given__nonpositive_quantity__when__buying__then__reject_and_rollback_before_stock_queries(int quantity) {
        assertThrows(IllegalArgumentException.class,()->f.service.create(7,f.command(quantity)));
        verifyNoInteractions(f.products,f.skus,f.writerOrders,f.jdbc,f.outbox);assertEquals("rollback:writer",f.events.getLast());
    }

    @ParameterizedTest @ValueSource(strings={"missing-sku","missing-product","hidden","sold-out","deleted","insufficient-stock"})
    void given__source_stock_rejection__when__buying__then__preserve_failure_without_order_or_snapshot(String kind) {
        if(kind.equals("missing-sku"))doReturn(List.of()).when(f.skus).findProductIdsBySkuIdIn(anyCollection());
        if(kind.equals("missing-product"))doReturn(Optional.empty()).when(f.products).findByIdWithLock(90L);
        if(kind.equals("hidden"))f.product.updateStatus(ProductStatus.HIDDEN);
        if(kind.equals("sold-out"))f.product.updateStatus(ProductStatus.SOLD_OUT);
        if(kind.equals("deleted"))f.product.updateStatus(ProductStatus.DELETED);
        Class<? extends RuntimeException> expected=kind.startsWith("missing")?IllegalArgumentException.class:IllegalStateException.class;
        assertThrows(expected,()->f.service.create(7,f.command(kind.equals("insufficient-stock")?6:1)));
        assertEquals(5,f.target.getStockQuantity());verifyNoInteractions(f.writerOrders,f.jdbc,f.outbox);assertEquals("rollback:writer",f.events.getLast());
    }

    @Test void given__long_price_overflow__when__buying__then__request_rollback_of_stock_and_followups_and_never_save_order() {
        f.target.updatePrice(Long.MAX_VALUE);
        var failure=assertThrows(IllegalArgumentException.class,()->f.service.create(7,f.command(2)));
        assertInstanceOf(ArithmeticException.class,failure.getCause());assertEquals("rollback:writer",f.events.getLast());
        assertEquals(2,f.snapshots.size());assertEquals(1,f.searchParameters.size());verifyNoInteractions(f.writerOrders);
        // This unit verifies the rollback request, not physical database restoration; Root owns the PG proof.
    }

    @ParameterizedTest @ValueSource(strings={"search","outbox1","outbox2","order"})
    void given__projection_or_outbox_or_order_failure__when__buying__then__propagate_and_request_one_writer_rollback(String stage) {
        f.failAt=stage;assertThrows(org.springframework.dao.DataAccessResourceFailureException.class,()->f.service.create(7,f.command(1)));
        assertEquals("rollback:writer",f.events.getLast());assertFalse(f.events.contains("commit:writer"));
        if(!stage.equals("order"))verifyNoInteractions(f.writerOrders);
    }

    @Test void given__commit_failure__when__creating__then__propagate_without_returning_created_success() {
        f.failAt="commit";assertThrows(TransactionSystemException.class,()->f.service.create(7,f.command(1)));
        assertEquals("commit:writer",f.events.getLast());
    }

    @ParameterizedTest @ValueSource(booleans={false,true})
    void given__wrong_owner_or_missing_order__when__reading__then__source_bad_input_without_writer_work(boolean missing) {
        if(!missing){var order=Order.builder().userId(8L).orderUuid("other").totalAmount(1L).build();DirectOrderFixture.id(order,33);f.orders.put(33L,order);}
        assertThrows(IllegalArgumentException.class,()->f.service.get(7,33));
        verifyNoInteractions(f.writerOrders,f.products,f.skus,f.jdbc,f.outbox);assertEquals("rollback:reader",f.events.getLast());
    }

    @Test void given__a_repeated_direct_purchase__when__creating_twice__then__keep_source_new_order_semantics_without_an_idempotency_key() {
        var first=f.service.create(7,f.command(1));var second=f.service.create(7,f.command(1));
        assertNotEquals(first.orderId(),second.orderId());assertNotEquals(first.orderUuid(),second.orderUuid());
        assertEquals(3,f.target.getStockQuantity());assertEquals(2,f.orders.size());assertEquals(4,f.snapshots.size());
    }

    @ParameterizedTest @ValueSource(strings={"write-in-ro","read-in-rw"})
    void given__miswired_transaction_mode__when__accessing__then__fail_closed_before_actor_or_store_rows(String kind) {
        if(kind.equals("write-in-ro"))f.write.setReadOnly(true);else f.read.setReadOnly(false);
        assertThrows(IllegalStateException.class,()->{if(kind.equals("write-in-ro"))f.service.create(7,f.command(1));else f.service.get(7,33);});
        verifyNoInteractions(f.actors,f.products,f.skus,f.writerOrders,f.readerOrders,f.jdbc,f.outbox);
    }

    @ParameterizedTest @ValueSource(strings={"create","get"})
    void given__no_actual_transaction__when__accessing__then__reject_before_actor_or_store_rows(String operation) {
        var absent=org.springframework.transaction.support.TransactionOperations.withoutTransaction();
        var service=new JpaDirectOrders(f.writerOrders,f.readerOrders,f.skus,f.stocks,f.actors,absent,absent);
        assertThrows(IllegalStateException.class,()->{if(operation.equals("create"))service.create(7,f.command(1));else service.get(7,33);});
        verifyNoInteractions(f.actors,f.products,f.skus,f.writerOrders,f.readerOrders,f.jdbc,f.outbox);
    }

    @ParameterizedTest @NullSource @EnumSource(OrderStatus.class)
    void given__optional_status_and_own_snapshot__when__listing_then_reading_detail__then__reuse_owner_query_ro_actor_and_identical_snapshot(OrderStatus status) {
        var order=historyOrder(status==null?OrderStatus.PENDING:status);
        var query=new StoreMemberReads.Request(1,10,List.of());
        org.mockito.stubbing.Answer<org.springframework.data.domain.Page<Order>> page=call->{
            f.reader();f.events.add("history-page");
            Pageable pageable=call.getArgument(call.getArguments().length-1);
            assertEquals(1,pageable.getPageNumber());assertEquals(10,pageable.getPageSize());assertTrue(pageable.getSort().isUnsorted());
            return new PageImpl<>(List.of(order),pageable,11);
        };
        if(status==null)when(f.readerOrders.findByUserIdOrderByCreatedAtDesc(eq(7L),any())).thenAnswer(page);
        else when(f.readerOrders.findByUserIdAndStatusOrderByCreatedAtDesc(eq(7L),eq(status),any())).thenAnswer(page);
        when(f.readerOrders.findWithItemsByUserIdAndIdIn(7L,List.of(77L))).thenAnswer(call->{
            f.reader();f.events.add("history-items");return List.of(order);
        });
        var result=f.service.list(7,status==null?null:status.name(),query);
        assertEquals(11,result.totalElements());assertEquals(1,result.number());assertEquals(10,result.size());assertTrue(result.sort().isEmpty());
        assertEquals(List.of("begin:reader","actor:read","history-page","history-items","commit:reader"),f.events);
        f.product.updateName("later live product");f.target.updatePrice(999L);
        assertEquals(result.content().getFirst(),f.service.get(7,77));
        assertEquals("purchased product",result.content().getFirst().items().getFirst().productName());
        assertEquals("PURCHASED-80",result.content().getFirst().items().getFirst().skuCode());
        assertEquals(17L,result.content().getFirst().items().getFirst().price());
        if(status==null)verify(f.readerOrders,never()).findByUserIdAndStatusOrderByCreatedAtDesc(anyLong(),any(),any());
        else verify(f.readerOrders,never()).findByUserIdOrderByCreatedAtDesc(anyLong(),any());
        verify(f.readerOrders).findWithItemsByUserIdAndIdIn(7L,List.of(77L));
        verifyNoInteractions(f.writerOrders,f.skus,f.products,f.jdbc,f.outbox);
        verify(f.actors,never()).currentForWrite(anyLong());
    }

    @ParameterizedTest @ValueSource(strings={"null","mismatch","inactive","deleted"})
    void given__missing_or_ineligible_current_member__when__listing__then__reject_before_any_order_query(String state) {
        f.actor=switch(state){case "null"->null;case "mismatch"->new StoreOrderActors.Actor(8,true,false);case "inactive"->new StoreOrderActors.Actor(7,false,false);default->new StoreOrderActors.Actor(7,true,true);};
        assertThrows(OrderActorRejectedException.class,()->f.service.list(7,null,new StoreMemberReads.Request(0,10,List.of())));
        verifyNoInteractions(f.readerOrders,f.writerOrders,f.products,f.skus,f.jdbc,f.outbox);
        assertEquals(List.of("begin:reader","actor:read","rollback:reader"),f.events);
    }

    static Stream<Arguments> invalidHistoryPages(){return Stream.of(
            Arguments.of(new StoreMemberReads.Request(0,101,List.of())),
            Arguments.of(new StoreMemberReads.Request(Integer.MAX_VALUE,100,List.of())),
            Arguments.of(new StoreMemberReads.Request(0,10,List.of(new StoreMemberReads.Order("orderItems.order.orderItems.order.id",true,false)))),
            Arguments.of(new StoreMemberReads.Request(0,10,List.of(new StoreMemberReads.Order("unknown",true,false)))),
            Arguments.of(new StoreMemberReads.Request(0,10,Collections.nCopies(5,new StoreMemberReads.Order("id",true,false)))));}
    @ParameterizedTest @MethodSource("invalidHistoryPages")
    void given__oversized_or_recursive_or_large_offset_query__when__listing__then__reject_before_transaction_or_actor(StoreMemberReads.Request request) {
        assertThrows(IllegalArgumentException.class,()->f.service.list(7,null,request));assertTrue(f.events.isEmpty());
        verifyNoInteractions(f.actors,f.readerOrders,f.writerOrders,f.products,f.skus,f.jdbc,f.outbox);
    }

    @Test void given__valid_scalar_sorts__when__listing_empty_page__then__preserve_order_direction_and_ignore_case_without_relationship_paths() {
        var sort=List.of(new StoreMemberReads.Order("totalAmount",true,false),new StoreMemberReads.Order("receiverName",false,true));
        when(f.readerOrders.findByUserIdOrderByCreatedAtDesc(eq(7L),any())).thenAnswer(call->{
            Pageable page=call.getArgument(1);assertEquals("totalAmount",page.getSort().toList().getFirst().getProperty());
            assertTrue(page.getSort().getOrderFor("totalAmount").isAscending());assertTrue(page.getSort().getOrderFor("receiverName").isIgnoreCase());
            return new PageImpl<>(List.<Order>of(),page,0);
        });
        var result=f.service.list(7,null,new StoreMemberReads.Request(0,100,sort));
        assertTrue(result.content().isEmpty());assertEquals(0,result.totalElements());assertEquals(100,result.size());assertEquals(sort,result.sort());
        verify(f.readerOrders,never()).findWithItemsByUserIdAndIdIn(anyLong(),anyList());
        verifyNoInteractions(f.writerOrders,f.products,f.skus,f.jdbc,f.outbox);
    }

    @ParameterizedTest @ValueSource(strings={"no-tx","read-in-rw"})
    void given__miswired_history_read_transaction__when__listing__then__fail_before_actor_or_store_rows(String kind) {
        var reads=kind.equals("no-tx")?org.springframework.transaction.support.TransactionOperations.withoutTransaction():f.write;
        var service=new JpaDirectOrders(f.writerOrders,f.readerOrders,f.skus,f.stocks,f.actors,f.write,reads);
        assertThrows(IllegalStateException.class,()->service.list(7,null,new StoreMemberReads.Request(0,10,List.of())));
        verifyNoInteractions(f.actors,f.readerOrders,f.writerOrders,f.products,f.skus,f.jdbc,f.outbox);
    }

    @Test void given__history_query_failure__when__listing__then__propagate_and_rollback_read_transaction_without_writes() {
        when(f.readerOrders.findByUserIdOrderByCreatedAtDesc(eq(7L),any())).thenAnswer(call->{
            f.reader();f.events.add("history-page");throw new org.springframework.dao.DataAccessResourceFailureException("private history query");
        });
        assertThrows(org.springframework.dao.DataAccessResourceFailureException.class,
                ()->f.service.list(7,null,new StoreMemberReads.Request(0,10,List.of())));
        assertEquals(List.of("begin:reader","actor:read","history-page","rollback:reader"),f.events);
        verifyNoInteractions(f.writerOrders,f.products,f.skus,f.jdbc,f.outbox);
    }

    @Test void given__nonempty_parent_page_and_different_bulk_result_order__when__listing__then__fetch_only_verified_owner_page_ids_once_and_preserve_parent_order_and_metadata() {
        var first=historyOrder(77,OrderStatus.PAID);var second=historyOrder(88,OrderStatus.PAID);
        var sort=List.of(new StoreMemberReads.Order("totalAmount",true,false));
        when(f.readerOrders.findByUserIdAndStatusOrderByCreatedAtDesc(eq(7L),eq(OrderStatus.PAID),any())).thenAnswer(call->{
            f.reader();f.events.add("history-page");return new PageImpl<>(List.of(second,first),call.getArgument(2),6);
        });
        when(f.readerOrders.findWithItemsByUserIdAndIdIn(7L,List.of(88L,77L))).thenAnswer(call->{
            f.reader();f.events.add("history-items");return List.of(first,second);
        });
        var result=f.service.list(7,"PAID",new StoreMemberReads.Request(1,2,sort));
        assertEquals(List.of(88L,77L),result.content().stream().map(DirectOrders.OrderDetails::orderId).toList());
        assertEquals(1,result.number());assertEquals(2,result.size());assertEquals(6,result.totalElements());assertEquals(sort,result.sort());
        assertEquals(List.of("begin:reader","actor:read","history-page","history-items","commit:reader"),f.events);
        verify(f.readerOrders).findWithItemsByUserIdAndIdIn(7L,List.of(88L,77L));
        verify(f.readerOrders,never()).findByUserIdOrderByCreatedAtDesc(anyLong(),any());
        verifyNoInteractions(f.writerOrders,f.products,f.skus,f.jdbc,f.outbox);
    }

    @Test void given__page_item_bulk_query_failure__when__listing__then__propagate_and_rollback_same_read_transaction_without_writes() {
        var order=historyOrder(OrderStatus.PENDING);
        when(f.readerOrders.findByUserIdOrderByCreatedAtDesc(eq(7L),any())).thenAnswer(call->{
            f.reader();f.events.add("history-page");return new PageImpl<>(List.of(order),call.getArgument(1),1);
        });
        when(f.readerOrders.findWithItemsByUserIdAndIdIn(7L,List.of(77L))).thenAnswer(call->{
            f.reader();f.events.add("history-items");throw new org.springframework.dao.DataAccessResourceFailureException("private bulk query");
        });
        assertThrows(org.springframework.dao.DataAccessResourceFailureException.class,
                ()->f.service.list(7,null,new StoreMemberReads.Request(0,10,List.of())));
        assertEquals(List.of("begin:reader","actor:read","history-page","history-items","rollback:reader"),f.events);
        verify(f.readerOrders).findWithItemsByUserIdAndIdIn(7L,List.of(77L));
        verifyNoInteractions(f.writerOrders,f.products,f.skus,f.jdbc,f.outbox);
    }

    private Order historyOrder(OrderStatus status) {
        return historyOrder(77,status);
    }
    private Order historyOrder(long orderId,OrderStatus status) {
        var order=Order.builder().userId(7L).orderUuid("owned-history-uuid").totalAmount(34L).receiverName("snapshot receiver")
                .receiverPhone("snapshot phone").deliveryAddress("snapshot address").deliveryMessage("snapshot message").build();
        order.updateStatus(status);DirectOrderFixture.id(order,orderId);org.springframework.test.util.ReflectionTestUtils.setField(order,"createdAt",f.time);
        order.getOrderItems().add(OrderItem.builder().order(order).productSKU(f.target).productName("purchased product").skuCode("PURCHASED-80").price(17L).quantity(2).build());
        f.orders.put(orderId,order);return order;
    }
}
