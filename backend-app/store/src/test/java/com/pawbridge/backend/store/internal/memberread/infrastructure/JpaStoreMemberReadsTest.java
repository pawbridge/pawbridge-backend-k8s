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
                Arguments.of(true,"sku.product.category.parent.name"),
                Arguments.of(false,"orderUuid"),Arguments.of(false,"receiverName"));
    }
    @ParameterizedTest @MethodSource("sortRequests")
    void given__nested_or_alias_sort__when__reading_pages__then__delegate_unchanged_to_the_original_repository_inside_read_transaction(boolean wishlist,String property) {
        var input = new StoreMemberReads.Request(3,7,List.of(new StoreMemberReads.Order(property,true,true)));
        var expected = PageRequest.of(3,7,Sort.by(new Sort.Order(Sort.Direction.ASC,property,true,Sort.NullHandling.NATIVE)));
        if(wishlist) when(wishlists.findIdsByUserId(7L,expected)).thenAnswer(call->{assertTrue(read.active);return org.springframework.data.domain.Page.empty(expected);});
        else when(orders.findIdsByUserId(7L,expected)).thenAnswer(call->{assertTrue(read.active);return org.springframework.data.domain.Page.empty(expected);});
        var result = wishlist ? store.wishlists(7,input) : store.orders(7,input);
        assertEquals(new StoreMemberReads.Page<>(List.of(),3,7,0L,input.sort()),result);
        if(wishlist) {verify(wishlists).findIdsByUserId(7L,expected);verifyNoMoreInteractions(wishlists);verifyNoInteractions(orders);}
        else {verify(orders).findIdsByUserId(7L,expected);verifyNoMoreInteractions(orders);verifyNoInteractions(wishlists);}
        verifyNoInteractions(carts);assertEquals(1,read.calls);assertFalse(read.active);
    }

    @ParameterizedTest @ValueSource(strings={"sku.createdAt","sku.updatedAt","sku.product.category.description",
            "sku.product.category.createdAt","sku.product.category.updatedAt"})
    void given__legacy_jdbc_wishlist_scalar_sort__when__reading_wishlist_page__then__preserve_property_and_delegate_inside_one_read_transaction(String property) {
        var input = new StoreMemberReads.Request(3,7,List.of(new StoreMemberReads.Order(property,true,false)));
        var expected = PageRequest.of(3,7,Sort.by(Sort.Order.asc(property)));
        when(wishlists.findIdsByUserId(7L,expected)).thenAnswer(call->{assertTrue(read.active);return org.springframework.data.domain.Page.empty(expected);});
        var result = store.wishlists(7,input);
        assertEquals(new StoreMemberReads.Page<>(List.of(),3,7,0L,input.sort()),result);
        verify(wishlists).findIdsByUserId(7L,expected);verifyNoMoreInteractions(wishlists);verifyNoInteractions(orders,carts);
        assertEquals(1,read.calls);assertFalse(read.active);
    }

    static Stream<Arguments> blockedSortRequests() {
        return Stream.of(true,false).flatMap(wishlist -> {
            String deep=wishlist ? "sku.product.category.parent.parent.name" : "orderItems.order.orderItems.price";
            return Stream.concat(Stream.of("unknown"," ","sum(price)",null,deep,deep.replace('.', '_'),
                    wishlist ? "skuProductCategoryParentParentName" : "orderItemsOrderOrderItemsPrice",
                    wishlist ? "orderItems.price" : "sku.price")
                    .map(property -> Arguments.of(wishlist,List.of(new StoreMemberReads.Order(property,true,false)))),
                    Stream.of(Arguments.of(wishlist,java.util.Collections.nCopies(5,new StoreMemberReads.Order("createdAt",true,false)))));
        });
    }
    @ParameterizedTest(name="blockedSort[{index}]") @MethodSource("blockedSortRequests")
    void given__unsupported_or_excessive_sort__when__reading_pages__then__reject_with_fixed_message_before_transaction_or_repository(boolean wishlist,List<StoreMemberReads.Order> sort) {
        var input=new StoreMemberReads.Request(0,2,sort);
        var failure=assertThrows(IllegalArgumentException.class,()->{if(wishlist)store.wishlists(7,input);else store.orders(7,input);});
        assertEquals("Store member sort is not supported",failure.getMessage());
        assertEquals(0,read.calls);assertFalse(read.active);verifyNoInteractions(wishlists,orders,carts);
    }

    static Stream<Arguments> collectionSortRequests() {
        return Stream.concat(Stream.of("sku.skuValues.optionValue.name","sku_skuValues_optionValue_name").map(property->Arguments.of(true,property)),
                Stream.of("orderItems.id","orderItems.productName","orderItems.skuCode","orderItems.price","orderItems.quantity",
                        "orderItems.productSKU.id","orderItems.productSKU.skuCode","orderItems.productSKU.price",
                        "orderItems_price","orderItems_productSKU_price","orderItemsProductSKUPrice").map(property->Arguments.of(false,property)));
    }
    @ParameterizedTest @MethodSource("collectionSortRequests")
    void given__new_collection_sort_path__when__reading_parent_pages__then__reject_with_fixed_message_before_transaction_or_repository(boolean wishlist,String property) {
        var input=new StoreMemberReads.Request(0,2,List.of(new StoreMemberReads.Order(property,true,false)));
        var failure=assertThrows(IllegalArgumentException.class,()->{if(wishlist)store.wishlists(7,input);else store.orders(7,input);});
        assertEquals("Store member sort is not supported",failure.getMessage());
        assertEquals(0,read.calls);assertFalse(read.active);verifyNoInteractions(wishlists,orders,carts);
    }

    @ParameterizedTest @ValueSource(booleans={true,false})
    void given__page_size_above_bound__when__reading_pages__then__reject_with_fixed_message_before_transaction_or_repository(boolean wishlist) {
        var input=new StoreMemberReads.Request(0,2001,request.sort());
        var failure=assertThrows(IllegalArgumentException.class,()->{if(wishlist)store.wishlists(7,input);else store.orders(7,input);});
        assertEquals("Store member sort is not supported",failure.getMessage());
        assertEquals(0,read.calls);assertFalse(read.active);verifyNoInteractions(wishlists,orders,carts);
    }

    @ParameterizedTest @ValueSource(booleans={true,false})
    void given__page_size_at_bound__when__reading_pages__then__preserve_page_size_and_skip_empty_graph(boolean wishlist) {
        var input=new StoreMemberReads.Request(0,2000,request.sort());
        var expected=PageRequest.of(0,2000,Sort.by(Sort.Order.desc("createdAt")));
        if(wishlist)when(wishlists.findIdsByUserId(7L,expected)).thenAnswer(call->{assertTrue(read.active);return org.springframework.data.domain.Page.empty(expected);});
        else when(orders.findIdsByUserId(7L,expected)).thenAnswer(call->{assertTrue(read.active);return org.springframework.data.domain.Page.empty(expected);});
        var result=wishlist?store.wishlists(7,input):store.orders(7,input);
        assertEquals(new StoreMemberReads.Page<>(List.of(),0,2000,0L,input.sort()),result);
        if(wishlist){verify(wishlists).findIdsByUserId(7L,expected);verifyNoMoreInteractions(wishlists);verifyNoInteractions(orders);}
        else{verify(orders).findIdsByUserId(7L,expected);verifyNoMoreInteractions(orders);verifyNoInteractions(wishlists);}
        assertEquals(1,read.calls);assertFalse(read.active);verifyNoInteractions(carts);
    }

    @ParameterizedTest @ValueSource(booleans={true,false})
    void given__four_allowed_sort_terms__when__reading_pages__then__preserve_every_term_inside_one_read_transaction(boolean wishlist) {
        var sort=List.of(new StoreMemberReads.Order("id",true,false),new StoreMemberReads.Order("userId",false,false),
                new StoreMemberReads.Order("createdAt",true,false),new StoreMemberReads.Order("updatedAt",false,false));
        var input=new StoreMemberReads.Request(0,2,sort);
        var expected=PageRequest.of(0,2,Sort.by(Sort.Order.asc("id"),Sort.Order.desc("userId"),Sort.Order.asc("createdAt"),Sort.Order.desc("updatedAt")));
        if(wishlist)when(wishlists.findIdsByUserId(7L,expected)).thenAnswer(call->{assertTrue(read.active);return org.springframework.data.domain.Page.empty(expected);});
        else when(orders.findIdsByUserId(7L,expected)).thenAnswer(call->{assertTrue(read.active);return org.springframework.data.domain.Page.empty(expected);});
        var result=wishlist ? store.wishlists(7,input) : store.orders(7,input);
        assertEquals(sort,result.sort());assertEquals(1,read.calls);assertFalse(read.active);
        if(wishlist){verify(wishlists).findIdsByUserId(7L,expected);verifyNoInteractions(orders);}else{verify(orders).findIdsByUserId(7L,expected);verifyNoInteractions(wishlists);}
        verifyNoInteractions(carts);
    }

    @ParameterizedTest @ValueSource(booleans={true,false})
    void given__reversed_and_repeated_graph_roots__when__reading_selected_parent_page__then__restore_id_order_once_and_keep_page_metadata_inside_transaction(boolean wishlist) {
        var input=new StoreMemberReads.Request(2,2,request.sort());
        var expected=PageRequest.of(2,2,Sort.by(Sort.Order.desc("createdAt")));var selected=List.of(33L,11L);
        if(wishlist) {
            Product product=Product.builder().name("stored name").status(ProductStatus.ACTIVE).build();id(product,90L);
            ProductSKU sku=ProductSKU.builder().product(product).skuCode("stored sku").price(1L).stockQuantity(1).build();id(sku,80L);
            var first=com.pawbridge.backend.store.internal.memberread.infrastructure.jpa.model.Wishlist.builder().userId(7L).sku(sku).build();id(first,33L);
            var second=com.pawbridge.backend.store.internal.memberread.infrastructure.jpa.model.Wishlist.builder().userId(7L).sku(sku).build();id(second,11L);
            when(wishlists.findIdsByUserId(7L,expected)).thenAnswer(call->{assertTrue(read.active);return new PageImpl<>(
                    List.of((MyWishlistRepository.IdOnly)()->33L,(MyWishlistRepository.IdOnly)()->11L),expected,9);});
            when(wishlists.findByUserIdAndIdIn(7L,selected)).thenAnswer(call->{assertTrue(read.active);return List.of(second,first,first);});
            var result=store.wishlists(7,input);assertEquals(selected,result.content().stream().map(StoreMemberReads.Wishlist::wishlistId).toList());
            assertEquals(9L,result.totalElements());assertEquals(2,result.number());assertEquals(2,result.size());assertEquals(input.sort(),result.sort());
            var sequence=inOrder(wishlists);sequence.verify(wishlists).findIdsByUserId(7L,expected);sequence.verify(wishlists).findByUserIdAndIdIn(7L,selected);
            verifyNoMoreInteractions(wishlists);verifyNoInteractions(orders);
        } else {
            var first=com.pawbridge.backend.store.internal.memberread.infrastructure.jpa.model.Order.builder().userId(7L).orderUuid("first").totalAmount(1L).build();id(first,33L);
            var second=com.pawbridge.backend.store.internal.memberread.infrastructure.jpa.model.Order.builder().userId(7L).orderUuid("second").totalAmount(2L).build();id(second,11L);
            when(orders.findIdsByUserId(7L,expected)).thenAnswer(call->{assertTrue(read.active);return new PageImpl<>(
                    List.of((MyOrderRepository.IdOnly)()->33L,(MyOrderRepository.IdOnly)()->11L),expected,9);});
            when(orders.findByUserIdAndIdIn(7L,selected)).thenAnswer(call->{assertTrue(read.active);return List.of(second,first,first);});
            var result=store.orders(7,input);assertEquals(selected,result.content().stream().map(StoreMemberReads.Purchase::orderId).toList());
            assertEquals(9L,result.totalElements());assertEquals(2,result.number());assertEquals(2,result.size());assertEquals(input.sort(),result.sort());
            var sequence=inOrder(orders);sequence.verify(orders).findIdsByUserId(7L,expected);sequence.verify(orders).findByUserIdAndIdIn(7L,selected);
            verifyNoMoreInteractions(orders);verifyNoInteractions(wishlists);
        }
        assertEquals(1,read.calls);assertFalse(read.active);verifyNoInteractions(carts);
    }

    @ParameterizedTest @ValueSource(booleans={true,false})
    void given__empty_parent_id_page__when__reading_pages__then__skip_graph_and_preserve_total_and_requested_sort(boolean wishlist) {
        var input=new StoreMemberReads.Request(3,2,request.sort());
        var expected=PageRequest.of(3,2,Sort.by(Sort.Order.desc("createdAt")));
        if(wishlist)when(wishlists.findIdsByUserId(7L,expected)).thenAnswer(call->{assertTrue(read.active);return new PageImpl<MyWishlistRepository.IdOnly>(List.of(),expected,5);});
        else when(orders.findIdsByUserId(7L,expected)).thenAnswer(call->{assertTrue(read.active);return new PageImpl<MyOrderRepository.IdOnly>(List.of(),expected,5);});
        var result=wishlist?store.wishlists(7,input):store.orders(7,input);
        assertEquals(new StoreMemberReads.Page<>(List.of(),3,2,5L,input.sort()),result);
        if(wishlist){verify(wishlists).findIdsByUserId(7L,expected);verifyNoMoreInteractions(wishlists);verifyNoInteractions(orders);}
        else{verify(orders).findIdsByUserId(7L,expected);verifyNoMoreInteractions(orders);verifyNoInteractions(wishlists);}
        assertEquals(1,read.calls);assertFalse(read.active);verifyNoInteractions(carts);
    }

    @ParameterizedTest @ValueSource(booleans={true,false})
    void given__selected_parent_missing_from_graph__when__reading_pages__then__fail_with_fixed_unavailable_boundary_before_mapping(boolean wishlist) {
        if(wishlist) {
            when(wishlists.findIdsByUserId(eq(7L),any())).thenAnswer(call->{assertTrue(read.active);return new PageImpl<>(List.of((MyWishlistRepository.IdOnly)()->9L),call.getArgument(1),1);});
            when(wishlists.findByUserIdAndIdIn(7L,List.of(9L))).thenAnswer(call->{assertTrue(read.active);return List.of();});
        } else {
            when(orders.findIdsByUserId(eq(7L),any())).thenAnswer(call->{assertTrue(read.active);return new PageImpl<>(List.of((MyOrderRepository.IdOnly)()->9L),call.getArgument(1),1);});
            when(orders.findByUserIdAndIdIn(7L,List.of(9L))).thenAnswer(call->{assertTrue(read.active);return List.of();});
        }
        var failure=assertThrows(StoreMemberReadUnavailableException.class,()->{if(wishlist)store.wishlists(7,request);else store.orders(7,request);});
        assertEquals("Store member information could not be read",failure.getMessage());
        assertInstanceOf(PersistenceException.class,failure.getCause());assertEquals("Store member page could not be read",failure.getCause().getMessage());
        assertEquals(1,read.calls);assertFalse(read.active);verifyNoInteractions(carts);
        if(wishlist)verifyNoInteractions(orders);else verifyNoInteractions(wishlists);
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
        when(wishlists.findIdsByUserId(eq(7L),any())).thenAnswer(call->{assertTrue(read.active);return new PageImpl<>(
                List.of((MyWishlistRepository.IdOnly)()->wishlist.getId()),call.getArgument(1),4);});
        when(wishlists.findByUserIdAndIdIn(7L,List.of(9007199254740993L))).thenAnswer(call->{assertTrue(read.active);return List.of(wishlist);});
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
        when(orders.findIdsByUserId(eq(7L),any())).thenAnswer(call->{assertTrue(read.active);return new PageImpl<>(
                List.of((MyOrderRepository.IdOnly)()->order.getId()),call.getArgument(1),1);});
        when(orders.findByUserIdAndIdIn(7L,List.of(33L))).thenAnswer(call->{assertTrue(read.active);return List.of(order);});
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
        if(wishlist) doThrow(failure).when(wishlists).findIdsByUserId(eq(7L),any());
        else doThrow(failure).when(orders).findIdsByUserId(eq(7L),any());
        var result = assertThrows(StoreMemberReadUnavailableException.class,()->{if(wishlist)store.wishlists(7,request);else store.orders(7,request);});
        assertSame(failure,result.getCause());assertEquals("Store member information could not be read",result.getMessage());
        assertFalse(read.active);assertEquals(1,read.calls);verifyNoInteractions(carts);
    }

    @ParameterizedTest @MethodSource("failures")
    void given__selected_graph_failure__when__reading_pages__then__keep_jdk_failure_boundary_and_original_cause(boolean wishlist,RuntimeException failure) {
        if(wishlist) {
            when(wishlists.findIdsByUserId(eq(7L),any())).thenAnswer(call->{assertTrue(read.active);return new PageImpl<>(List.of((MyWishlistRepository.IdOnly)()->9L),call.getArgument(1),1);});
            doAnswer(call->{assertTrue(read.active);throw failure;}).when(wishlists).findByUserIdAndIdIn(7L,List.of(9L));
        } else {
            when(orders.findIdsByUserId(eq(7L),any())).thenAnswer(call->{assertTrue(read.active);return new PageImpl<>(List.of((MyOrderRepository.IdOnly)()->9L),call.getArgument(1),1);});
            doAnswer(call->{assertTrue(read.active);throw failure;}).when(orders).findByUserIdAndIdIn(7L,List.of(9L));
        }
        var result=assertThrows(StoreMemberReadUnavailableException.class,()->{if(wishlist)store.wishlists(7,request);else store.orders(7,request);});
        assertSame(failure,result.getCause());assertEquals("Store member information could not be read",result.getMessage());
        assertEquals(1,read.calls);assertFalse(read.active);verifyNoInteractions(carts);
        if(wishlist)verifyNoInteractions(orders);else verifyNoInteractions(wishlists);
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
            doThrow(new PersistenceException("private query")).when(wishlists).findIdsByUserId(eq(7L),any());
            doThrow(new PersistenceException("private query")).when(orders).findIdsByUserId(eq(7L),any());
            doThrow(failure).when(manager).rollback(status);
        }
        if(phase.equals("commit")) {
            when(wishlists.findIdsByUserId(eq(7L),any())).thenReturn(org.springframework.data.domain.Page.empty(PageRequest.of(0,1)));
            when(orders.findIdsByUserId(eq(7L),any())).thenReturn(org.springframework.data.domain.Page.empty(PageRequest.of(0,1)));
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
        when(wishlist.getId()).thenReturn(9L);
        when(wishlist.getSku()).thenAnswer(call->{assertTrue(read.active);throw failure;});
        when(wishlists.findIdsByUserId(eq(7L),any())).thenAnswer(call->{assertTrue(read.active);return new PageImpl<>(
                List.of((MyWishlistRepository.IdOnly)()->9L),call.getArgument(1),1);});
        when(wishlists.findByUserIdAndIdIn(7L,List.of(9L))).thenAnswer(call->{assertTrue(read.active);return List.of(wishlist);});
        var result = assertThrows(StoreMemberReadUnavailableException.class,()->store.wishlists(7,request));
        assertSame(failure,result.getCause());assertFalse(read.active);verifyNoInteractions(orders,carts);
    }

    private static void id(Object entity,long id) { ReflectionTestUtils.setField(entity,"id",id); }
    private static final class Read implements TransactionOperations {
        private boolean active;private int calls;
        public <T>T execute(TransactionCallback<T> callback) {calls++;active=true;try{return callback.doInTransaction(new SimpleTransactionStatus());}finally{active=false;}}
    }
}
