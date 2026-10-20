package com.pawbridge.backend.store.internal.directorder.infrastructure;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.pawbridge.backend.store.api.StoreOrderActors;
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
}
