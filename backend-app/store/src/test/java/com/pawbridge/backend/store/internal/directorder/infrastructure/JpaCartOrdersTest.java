package com.pawbridge.backend.store.internal.directorder.infrastructure;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.pawbridge.backend.store.api.StoreOrderActors;
import com.pawbridge.backend.store.internal.cart.infrastructure.jpa.model.Cart;
import com.pawbridge.backend.store.internal.cart.infrastructure.jpa.model.CartItem;
import com.pawbridge.backend.store.internal.cart.infrastructure.jpa.repository.CartRepository;
import com.pawbridge.backend.store.internal.directorder.application.CartOrders;
import com.pawbridge.backend.store.internal.directorder.application.OrderActorRejectedException;
import com.pawbridge.backend.store.internal.directorder.infrastructure.jpa.repository.ProductSKURepository;
import com.pawbridge.backend.store.internal.memberread.infrastructure.jpa.model.ProductStatus;
import jakarta.persistence.PersistenceException;
import java.util.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.support.TransactionOperations;

class JpaCartOrdersTest {
    DirectOrderFixture f;
    CartRepository carts;
    Cart cart;
    JpaCartOrders service;
    final CartOrders.Command command=new CartOrders.Command("fixture receiver","fixture phone","fixture address","fixture message");
    @BeforeEach void setup() {
        f=new DirectOrderFixture();carts=mock(CartRepository.class);cart=spy(Cart.builder().userId(7L).build());
        doAnswer(call->{f.writer();f.events.add("cart");if(f.failAt.equals("cart"))throw DirectOrderFixture.failure();return Optional.of(cart);}).when(carts).findByUserId(7L);
        doAnswer(call->{f.writer();f.events.add("cart-clear");return call.callRealMethod();}).when(cart).clearItems();
        doAnswer(call->{
            f.writer();f.events.add("reference");Collection<Long> ids=call.getArgument(0);
            return java.util.stream.Stream.of(80L,81L).filter(ids::contains).map(id->{
                var ref=mock(ProductSKURepository.SkuProductId.class);when(ref.getSkuId()).thenReturn(id);when(ref.getProductId()).thenReturn(90L);return ref;
            }).toList();
        }).when(f.skus).findProductIdsBySkuIdIn(anyCollection());
        doAnswer(call->{
            f.writer();f.events.add("sku-read-all");if(f.failAt.equals("read"))throw DirectOrderFixture.failure();
            Collection<Long> ids=call.getArgument(0);return java.util.stream.Stream.of(f.target,f.sibling).filter(sku->ids.contains(sku.getId())).toList();
        }).when(f.skus).findAllById(any());
        service=new JpaCartOrders(carts,f.writerOrders,f.skus,f.stocks,f.actors,f.write);
    }
    @AfterEach void close(){f.close();}
    void item(long id,int quantity){cart.addItem(CartItem.builder().skuId(id).quantity(quantity).build());}

    @Test void given__two_live_cart_skus__when__checking_out__then__one_transaction_preserves_original_order_and_stock_followups_and_clears_cart() {
        item(80,2);item(81,3);var result=service.create(7,command);
        assertEquals(350L,result.totalAmount());assertEquals(7L,result.userId());assertEquals("PENDING",result.status());assertEquals("READY",result.deliveryStatus());
        assertEquals("fixture receiver",result.receiverName());assertEquals("fixture phone",result.receiverPhone());assertEquals("fixture address",result.deliveryAddress());assertEquals("fixture message",result.deliveryMessage());
        assertEquals(f.time,result.createdAt());UUID.fromString(result.orderUuid());assertEquals(2,result.items().size());
        assertEquals(100L,result.items().getFirst().price());assertEquals(2,result.items().getFirst().quantity());assertEquals(50L,result.items().get(1).price());
        assertEquals(3,f.target.getStockQuantity());assertEquals(5,f.sibling.getStockQuantity());assertTrue(cart.getItems().isEmpty());
        assertEquals(2,f.snapshots.size());assertEquals(1,f.searchParameters.size());assertEquals(1,f.orders.size());
        assertEquals(List.of("begin:writer","actor:write","cart","reference","reference","product-lock","all-sku-lock","search","outbox:80","outbox:81","sku-read-all","order-save","cart-clear","commit:writer"),f.events);
        verify(f.skus,never()).findById(anyLong());verify(f.actors,never()).currentForRead(anyLong());verifyNoInteractions(f.readerOrders);
        var saved=f.orders.get(result.orderId());for(var row:saved.getOrderItems())assertSame(saved,row.getOrder());
    }
    @Test void given__price_changed_before_product_lock_acquired__when__checking_out__then__total_and_items_use_the_same_locked_current_price() {
        item(80,2);
        doAnswer(call->{f.writer();f.events.add("product-lock");f.target.updatePrice(200L);return Optional.of(f.product);}).when(f.products).findByIdWithLock(90L);
        var result=service.create(7,command);assertEquals(400L,result.totalAmount());assertEquals(200L,result.items().getFirst().price());
        assertTrue(f.events.indexOf("all-sku-lock")<f.events.indexOf("sku-read-all"));verify(f.skus,never()).findById(anyLong());
    }
    @ParameterizedTest @ValueSource(strings={"null","wrong","inactive","deleted"})
    void given__ineligible_current_actor__when__checking_out__then__reject_before_cart_or_store_rows(String kind) {
        f.actor=switch(kind){case "null"->null;case "wrong"->new StoreOrderActors.Actor(8,true,false);case "inactive"->new StoreOrderActors.Actor(7,false,false);default->new StoreOrderActors.Actor(7,true,true);};
        assertThrows(OrderActorRejectedException.class,()->service.create(7,command));verifyNoInteractions(carts,f.products,f.skus,f.writerOrders,f.jdbc,f.outbox);assertEquals("rollback:writer",f.events.getLast());
    }
    @ParameterizedTest @ValueSource(strings={"missing","empty","only-stale"})
    void given__no_visible_cart_items__when__checking_out__then__original_empty_error_without_consumption_or_stock_work(String kind) {
        if(kind.equals("missing"))doReturn(Optional.empty()).when(carts).findByUserId(7L);
        if(kind.equals("only-stale"))item(999,2);
        var failure=assertThrows(IllegalArgumentException.class,()->service.create(7,command));assertEquals("Cart is empty",failure.getMessage());
        verify(cart,never()).clearItems();verifyNoInteractions(f.products,f.writerOrders,f.jdbc,f.outbox);
        if(kind.equals("only-stale"))assertEquals(1,cart.getItems().size());
    }
    @Test void given__live_and_stale_cart_items__when__checking_out__then__purchase_visible_items_and_clear_whole_cart_like_original() {
        item(80,1);item(999,3);var result=service.create(7,command);
        assertEquals(100L,result.totalAmount());assertEquals(1,result.items().size());assertTrue(cart.getItems().isEmpty());assertEquals(4,f.target.getStockQuantity());
    }
    @Test void given__duplicate_cart_sku__when__checking_out__then__fail_closed_without_implicit_merge_or_stock_change() {
        item(80,1);item(80,2);assertThrows(PersistenceException.class,()->service.create(7,command));verifyNoInteractions(f.skus,f.products,f.writerOrders);verify(cart,never()).clearItems();
    }
    @ParameterizedTest @ValueSource(ints={0,-1})
    void given__nonpositive_stored_quantity__when__checking_out__then__reuse_original_stock_validation(int quantity) {
        item(80,quantity);assertThrows(IllegalArgumentException.class,()->service.create(7,command));verifyNoInteractions(f.products,f.writerOrders,f.jdbc,f.outbox);verify(cart,never()).clearItems();
    }
    @ParameterizedTest @ValueSource(strings={"inactive","stock","missing-under-lock"})
    void given__original_stock_rejection__when__checking_out__then__rollback_without_consuming_cart(String kind) {
        item(80,kind.equals("stock")?6:1);
        if(kind.equals("inactive"))f.product.updateStatus(ProductStatus.HIDDEN);
        if(kind.equals("missing-under-lock"))doReturn(List.of(f.sibling)).when(f.skus).findAllByProductIdWithLock(90L);
        Class<? extends RuntimeException> type=kind.equals("missing-under-lock")?IllegalArgumentException.class:IllegalStateException.class;
        assertThrows(type,()->service.create(7,command));assertEquals(5,f.target.getStockQuantity());verify(cart,never()).clearItems();verifyNoInteractions(f.writerOrders);assertEquals("rollback:writer",f.events.getLast());
    }
    @ParameterizedTest @ValueSource(strings={"multiply","sum"})
    void given__order_amount_overflow__when__checking_out__then__bad_input_requests_rollback_of_stock_and_projection_without_consuming_cart(String kind) {
        f.target.updatePrice(Long.MAX_VALUE);item(80,kind.equals("multiply")?2:1);
        if(kind.equals("sum")){f.sibling.updatePrice(1L);item(81,1);}
        var failure=assertThrows(IllegalArgumentException.class,()->service.create(7,command));assertInstanceOf(ArithmeticException.class,failure.getCause());
        assertEquals(2,f.snapshots.size());assertEquals(1,f.searchParameters.size());verifyNoInteractions(f.writerOrders);verify(cart,never()).clearItems();assertEquals("rollback:writer",f.events.getLast());
    }
    @ParameterizedTest @ValueSource(strings={"cart","search","outbox1","outbox2","read","order","commit"})
    void given__query_followup_order_or_commit_failure__when__checking_out__then__never_return_success(String stage) {
        item(80,1);f.failAt=stage;
        Class<? extends RuntimeException> type=stage.equals("commit")?TransactionSystemException.class:DataAccessResourceFailureException.class;
        assertThrows(type,()->service.create(7,command));
        if(!stage.equals("commit")){verify(cart,never()).clearItems();assertEquals(1,cart.getItems().size());assertEquals("rollback:writer",f.events.getLast());}
        // Mock persistence observes rollback requests; Root owns physical DB restoration after clear/commit failure.
    }
    @Test void given__selected_sku_missing_after_locks__when__materializing_order__then__fixed_failure_without_consuming_cart() {
        item(80,1);doReturn(List.of()).when(f.skus).findAllById(any());
        var failure=assertThrows(PersistenceException.class,()->service.create(7,command));assertEquals("Checkout items cannot be read",failure.getMessage());verify(cart,never()).clearItems();verifyNoInteractions(f.writerOrders);
    }
    @Test void given__already_consumed_cart_without_new_add__when__checking_out_again__then__empty_error_and_one_order_only() {
        item(80,1);service.create(7,command);assertThrows(IllegalArgumentException.class,()->service.create(7,command));assertEquals(1,f.orders.size());assertEquals(4,f.target.getStockQuantity());
    }
    @ParameterizedTest @ValueSource(strings={"none","read-only"})
    void given__invalid_writer_transaction__when__checking_out__then__fail_before_actor_or_cart(String kind) {
        var tx=kind.equals("none")?TransactionOperations.withoutTransaction():f.read;
        var service=new JpaCartOrders(carts,f.writerOrders,f.skus,f.stocks,f.actors,tx);
        assertThrows(IllegalStateException.class,()->service.create(7,command));verifyNoInteractions(carts,f.actors,f.skus,f.products,f.writerOrders);
    }
}
