package com.pawbridge.backend.store.internal.memberread.infrastructure;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.pawbridge.backend.store.api.StoreMemberReadUnavailableException;
import com.pawbridge.backend.store.api.StoreMemberReads;
import com.pawbridge.backend.store.internal.memberread.infrastructure.jpa.model.OptionGroup;
import com.pawbridge.backend.store.internal.memberread.infrastructure.jpa.model.OptionValue;
import com.pawbridge.backend.store.internal.memberread.infrastructure.jpa.model.Product;
import com.pawbridge.backend.store.internal.memberread.infrastructure.jpa.model.ProductSKU;
import com.pawbridge.backend.store.internal.memberread.infrastructure.jpa.model.ProductStatus;
import com.pawbridge.backend.store.internal.memberread.infrastructure.jpa.model.SKUValue;
import com.pawbridge.backend.store.internal.memberread.infrastructure.jpa.repository.MyOrderRepository;
import com.pawbridge.backend.store.internal.memberread.infrastructure.jpa.repository.MyWishlistRepository;
import jakarta.persistence.EntityNotFoundException;
import jakarta.persistence.PersistenceException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionTemplate;

class JpaStoreMemberReadsTest {
    private final MyWishlistRepository wishlists = mock(MyWishlistRepository.class);
    private final MyOrderRepository orders = mock(MyOrderRepository.class);
    private final StoreMemberReads carts = mock(StoreMemberReads.class);
    private final Read read = new Read();
    private final JpaStoreMemberReads store = new JpaStoreMemberReads(wishlists, orders, read, carts);
    private final StoreMemberReads.Request request = new StoreMemberReads.Request(0, 1,
            List.of(new StoreMemberReads.Order("createdAt", false, false)));
    private final LocalDateTime created = LocalDateTime.of(2026,10,20,3,4,5,123456789);

    static Stream<Arguments> sortRequests() {
        return Stream.of(Arguments.of(true,"skuPrice"),Arguments.of(true,"sku_price"),
                Arguments.of(true,"sku.product.category.parent.name"),Arguments.of(true,"sku.skuValues.optionValue.name"),
                Arguments.of(false,"orderItems.price"),Arguments.of(false,"orderItems.productSKU.price"));
    }
    @ParameterizedTest @MethodSource("sortRequests")
    void given__nested_or_alias_sort__when__reading_pages__then__delegate_unchanged_to_the_original_repository_inside_read_transaction(boolean wishlist,String property) {
        var input = new StoreMemberReads.Request(3,7,List.of(new StoreMemberReads.Order(property,true,true)));
        var expected = PageRequest.of(3,7,Sort.by(new Sort.Order(Sort.Direction.ASC,property,true,Sort.NullHandling.NATIVE)));
        if(wishlist) when(wishlists.findByUserId(7L,expected)).thenAnswer(call->{assertTrue(read.active);return org.springframework.data.domain.Page.empty(expected);});
        else when(orders.findByUserId(7L,expected)).thenAnswer(call->{assertTrue(read.active);return org.springframework.data.domain.Page.empty(expected);});
        var result = wishlist ? store.wishlists(7,input) : store.orders(7,input);
        assertEquals(new StoreMemberReads.Page<>(List.of(),3,7,0L,input.sort()),result);
        if(wishlist) {verify(wishlists).findByUserId(7L,expected);verifyNoInteractions(orders);}
        else {verify(orders).findByUserId(7L,expected);verifyNoInteractions(wishlists);}
        verifyNoInteractions(carts);assertEquals(1,read.calls);assertFalse(read.active);
    }

    @Test void given__wishlist_with_long_price_and_repeated_option_group__when__mapping__then__run_original_mapper_inside_transaction_and_preserve_all_fields() {
        Product product = Product.builder().name("stored name").status(ProductStatus.DELETED).build(); id(product,90L);
        ProductSKU sku = ProductSKU.builder().product(product).skuCode("stored sku").price(Long.MAX_VALUE).stockQuantity(0).build(); id(sku,80L);
        OptionGroup group = OptionGroup.builder().name("color").build();
        sku.getSkuValues().add(SKUValue.builder().productSKU(sku).optionValue(OptionValue.builder().optionGroup(group).name("red").build()).build());
        sku.getSkuValues().add(SKUValue.builder().productSKU(sku).optionValue(OptionValue.builder().optionGroup(group).name("blue").build()).build());
        var wishlist = spy(com.pawbridge.backend.store.internal.memberread.infrastructure.jpa.model.Wishlist.builder().userId(7L).sku(sku).build());
        id(wishlist,9007199254740993L);ReflectionTestUtils.setField(wishlist,"createdAt",created);
        doAnswer(call->{assertTrue(read.active);return sku;}).when(wishlist).getSku();
        when(wishlists.findByUserId(eq(7L),any())).thenReturn(new PageImpl<>(List.of(wishlist),PageRequest.of(0,1,Sort.by(Sort.Direction.DESC,"createdAt")),4));
        var result = store.wishlists(7,request);
        assertEquals(List.of(new StoreMemberReads.Wishlist(9007199254740993L,80L,"stored sku",90L,"stored name",null,null,
                "DELETED",Long.MAX_VALUE,Map.of("color","blue"),null,null,created)),result.content());
        assertEquals(4L,result.totalElements());assertEquals(request.sort(),result.sort());
        assertEquals(1,read.calls);assertFalse(read.active);verifyNoInteractions(orders,carts);
    }

    @Test void given__order_snapshot_different_from_live_product__when__mapping__then__keep_snapshot_long_values_and_complete_mapping_inside_transaction() {
        Product product = Product.builder().name("live name").status(ProductStatus.ACTIVE).build();
        ProductSKU sku = ProductSKU.builder().product(product).skuCode("live sku").price(1L).stockQuantity(0).build();id(sku,80L);
        var order = spy(com.pawbridge.backend.store.internal.memberread.infrastructure.jpa.model.Order.builder()
                .orderUuid("uuid").userId(7L).totalAmount(9007199254740993L).deliveryAddress("address").receiverName("receiver").receiverPhone("fixture").build());
        id(order,33L);order.cancelOrder();
        var item = com.pawbridge.backend.store.internal.memberread.infrastructure.jpa.model.OrderItem.builder()
                .order(order).productSKU(sku).productName("purchase name").skuCode("purchase sku").price(Long.MAX_VALUE).quantity(2).build();id(item,44L);
        var items = List.of(item);doAnswer(call->{assertTrue(read.active);return items;}).when(order).getOrderItems();
        when(orders.findByUserId(eq(7L),any())).thenReturn(new PageImpl<>(List.of(order),PageRequest.of(0,1,Sort.by(Sort.Direction.DESC,"createdAt")),1));
        var result = store.orders(7,request);
        assertEquals(List.of(new StoreMemberReads.Purchase(33L,"uuid",9007199254740993L,"CANCELLED","address","READY",
                List.of(new StoreMemberReads.OrderItem(44L,80L,"purchase name","purchase sku",Long.MAX_VALUE,2)),null)),result.content());
        assertEquals(1,read.calls);assertFalse(read.active);verifyNoInteractions(wishlists,carts);
    }

    @ParameterizedTest @ValueSource(booleans={false,true})
    void given__existing_cart_result__when__reading_cart__then__delegate_without_a_jpa_transaction(boolean present) {
        Optional<StoreMemberReads.Cart> expected = present ? Optional.of(new StoreMemberReads.Cart(9L,7L,List.of(),null)) : Optional.empty();
        when(carts.cart(7)).thenReturn(expected);assertSame(expected,store.cart(7));
        verify(carts).cart(7);verifyNoMoreInteractions(carts);verifyNoInteractions(wishlists,orders);assertEquals(0,read.calls);
    }

    static Stream<Arguments> failures() {
        return Stream.of(true,false).flatMap(wishlist -> Stream.of(
                Arguments.of(wishlist,new DataAccessResourceFailureException("private SQL")),
                Arguments.of(wishlist,new TransactionSystemException("private transaction")),
                Arguments.of(wishlist,new PersistenceException("private persistence"))));
    }
    @ParameterizedTest @MethodSource("failures")
    void given__repository_failure__when__reading_pages__then__keep_jdk_failure_boundary_and_original_cause(boolean wishlist,RuntimeException failure) {
        if(wishlist) doThrow(failure).when(wishlists).findByUserId(eq(7L),any());
        else doThrow(failure).when(orders).findByUserId(eq(7L),any());
        var result = assertThrows(StoreMemberReadUnavailableException.class,()->{if(wishlist)store.wishlists(7,request);else store.orders(7,request);});
        assertSame(failure,result.getCause());assertEquals("Store member information could not be read",result.getMessage());
        assertFalse(read.active);assertEquals(1,read.calls);verifyNoInteractions(carts);
    }

    static Stream<Arguments> transactionFailures() {
        return Stream.of(true,false).flatMap(wishlist -> Stream.of("begin","commit","rollback").map(phase->Arguments.of(wishlist,phase)));
    }
    @ParameterizedTest @MethodSource("transactionFailures")
    void given__transaction_boundary_failure__when__reading_pages__then__propagate_with_original_cause_without_success(boolean wishlist,String phase) {
        var manager = mock(PlatformTransactionManager.class);var status = new SimpleTransactionStatus();
        when(manager.getTransaction(any())).thenReturn(status);
        RuntimeException failure = phase.equals("begin") ? new CannotCreateTransactionException("private begin") : new TransactionSystemException("private "+phase);
        if(phase.equals("begin")) doThrow(failure).when(manager).getTransaction(any());
        else if(phase.equals("commit")) doThrow(failure).when(manager).commit(status);
        else {
            doThrow(new PersistenceException("private query")).when(wishlists).findByUserId(eq(7L),any());
            doThrow(new PersistenceException("private query")).when(orders).findByUserId(eq(7L),any());
            doThrow(failure).when(manager).rollback(status);
        }
        if(phase.equals("commit")) {
            when(wishlists.findByUserId(eq(7L),any())).thenReturn(org.springframework.data.domain.Page.empty(PageRequest.of(0,1)));
            when(orders.findByUserId(eq(7L),any())).thenReturn(org.springframework.data.domain.Page.empty(PageRequest.of(0,1)));
        }
        var candidate = new JpaStoreMemberReads(wishlists,orders,new TransactionTemplate(manager),carts);
        var result = assertThrows(StoreMemberReadUnavailableException.class,()->{if(wishlist)candidate.wishlists(7,request);else candidate.orders(7,request);});
        assertSame(failure,result.getCause());
        if(phase.equals("begin")) verifyNoInteractions(wishlists,orders);
        if(phase.equals("rollback")) verify(manager).rollback(status);
        verifyNoInteractions(carts);
    }

    @Test void given__lazy_association_failure__when__original_wishlist_mapper_runs__then__fail_inside_transaction_and_preserve_cause() {
        var wishlist = mock(com.pawbridge.backend.store.internal.memberread.infrastructure.jpa.model.Wishlist.class);
        var failure = new EntityNotFoundException("private lazy association");
        when(wishlist.getSku()).thenAnswer(call->{assertTrue(read.active);throw failure;});
        when(wishlists.findByUserId(eq(7L),any())).thenReturn(new PageImpl<>(List.of(wishlist),PageRequest.of(0,1),1));
        var result = assertThrows(StoreMemberReadUnavailableException.class,()->store.wishlists(7,request));
        assertSame(failure,result.getCause());assertFalse(read.active);verifyNoInteractions(orders,carts);
    }

    private static void id(Object entity,long id) { ReflectionTestUtils.setField(entity,"id",id); }
    private static final class Read implements TransactionOperations {
        private boolean active;private int calls;
        public <T>T execute(TransactionCallback<T> callback) {calls++;active=true;try{return callback.doInTransaction(new SimpleTransactionStatus());}finally{active=false;}}
    }
}
