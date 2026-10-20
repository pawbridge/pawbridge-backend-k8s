package com.pawbridge.backend.user.internal.storemypage.infrastructure;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.pawbridge.backend.store.api.StoreMemberReads;
import com.pawbridge.backend.store.api.StoreMemberReadUnavailableException;
import com.pawbridge.backend.user.internal.profile.application.UserNotFoundException;
import com.pawbridge.backend.user.internal.profile.application.UserProfileQuery;
import com.pawbridge.backend.user.internal.storemypage.application.StoreMyPage;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.MethodParameter;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.data.web.PageableHandlerMethodArgumentResolver;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.web.context.request.NativeWebRequest;

class ModuleStoreMyPageTest {
    private static final StoreMyPage.Request DEFAULT = new StoreMyPage.Request(0,20,"createdAt,desc");
    private static final List<StoreMemberReads.Order> SORT = List.of(new StoreMemberReads.Order("createdAt",false,false));

    @ParameterizedTest @ValueSource(strings={"wishlists","orders","cart"})
    void given__the_current_member__when__reading_each_store_result__then__finish_user_transaction_before_calling_store_with_that_id(String operation) {
        var events=new ArrayList<String>(); var read=new Read(events); var profiles=mock(UserProfileQuery.class); var store=mock(StoreMemberReads.class);
        when(profiles.require(7)).thenAnswer(call->{ assertTrue(read.active);events.add("member");return null; });
        storeAnswer(store,operation,()->{ assertFalse(read.active);events.add("store");return empty(operation); });
        call(new ModuleStoreMyPage(profiles,read,store),operation);
        assertEquals(List.of("begin","member","commit","end","store"),events);
        verify(profiles).require(7);
        switch(operation) {
            case "wishlists" -> verify(store).wishlists(7,new StoreMemberReads.Request(0,20,SORT));
            case "orders" -> verify(store).orders(7,new StoreMemberReads.Request(0,20,SORT));
            default -> verify(store).cart(7);
        }
        verifyNoMoreInteractions(store);
    }

    @ParameterizedTest @ValueSource(strings={"wishlists","orders","cart"})
    void given__a_missing_current_member__when__reading__then__preserve_user_failure_without_store_call(String operation) {
        var profiles=mock(UserProfileQuery.class);var store=mock(StoreMemberReads.class);var failure=new UserNotFoundException();
        when(profiles.require(7)).thenThrow(failure);
        assertSame(failure,assertThrows(UserNotFoundException.class,()->call(new ModuleStoreMyPage(profiles,new Read(new ArrayList<>()),store),operation)));
        verifyNoInteractions(store);
    }

    @ParameterizedTest @CsvSource({"begin,cart","commit,orders"})
    void given__a_user_transaction_failure__when__reading__then__propagate_it_before_any_store_work(String phase,String operation) {
        var profiles=mock(UserProfileQuery.class);var store=mock(StoreMemberReads.class);var read=new Read(new ArrayList<>());
        var failure=new IllegalStateException("synthetic user transaction failure");read.failure=failure;read.phase=phase;
        assertSame(failure,assertThrows(IllegalStateException.class,()->call(new ModuleStoreMyPage(profiles,read,store),operation)));
        if (phase.equals("begin")) verifyNoInteractions(profiles);else verify(profiles).require(7);
        verifyNoInteractions(store);assertFalse(read.active);
    }

    @ParameterizedTest @ValueSource(strings={"wishlists","orders","cart"})
    void given__store_is_unavailable__when__reading__then__keep_fixed_user_failure_without_treating_it_as_missing_user(String operation) {
        var failure=new StoreMemberReadUnavailableException(new IllegalStateException("synthetic-private-store-detail"));
        var store=mock(StoreMemberReads.class);storeAnswer(store,operation,()->{ throw failure; });
        var caught=assertThrows(IllegalStateException.class,()->call(new ModuleStoreMyPage(id->null,new Read(new ArrayList<>()),store),operation));
        assertEquals(operation.equals("wishlists")?"Store wishlist lookup failed":operation.equals("orders")?"Store order lookup failed":"Store cart lookup failed",caught.getMessage());
        assertSame(failure,caught.getCause());assertFalse(caught.getMessage().contains("synthetic-private"));
    }

    @Test
    void given__full_store_projections__when__mapping__then__keep_only_existing_user_fields_nulls_precision_and_long_values() {
        var store=mock(StoreMemberReads.class);var time=LocalDateTime.of(2026,10,20,1,2,3,123456000);
        var wish=new StoreMemberReads.Wishlist(1L,99L,"not-in-user-subset",2L,"상품",null,null,"DELETED",Long.MAX_VALUE,Map.of("색","보라"),null,null,time);
        when(store.wishlists(7,new StoreMemberReads.Request(0,20,SORT))).thenReturn(new StoreMemberReads.Page<>(List.of(wish),0,20,1,SORT));
        var item=new StoreMemberReads.OrderItem(3L,4L,null,"snapshot",Long.MAX_VALUE,null);
        when(store.orders(7,new StoreMemberReads.Request(0,20,SORT))).thenReturn(new StoreMemberReads.Page<>(List.of(new StoreMemberReads.Purchase(5L,"order",Long.MAX_VALUE,"CANCELLED",null,null,List.of(item),null)),0,20,1,SORT));
        var cartItem=new StoreMemberReads.CartItem(6L,4L,null,null,0L,2,0L);
        when(store.cart(7)).thenReturn(Optional.of(new StoreMemberReads.Cart(8L,7L,List.of(cartItem),time)));
        var pages=new ModuleStoreMyPage(id->null,new Read(new ArrayList<>()),store);
        assertEquals(new StoreMyPage.Wishlist(1L,2L,"상품",null,null,"DELETED",null,null,time),pages.wishlists(7,DEFAULT).content().getFirst());
        assertEquals(new StoreMyPage.Purchase(5L,"order",Long.MAX_VALUE,"CANCELLED",null,null,List.of(new StoreMyPage.OrderItem(3L,4L,null,"snapshot",Long.MAX_VALUE,null)),null),pages.orders(7,DEFAULT).content().getFirst());
        assertEquals(new StoreMyPage.Cart(8L,7L,List.of(new StoreMyPage.CartItem(6L,4L,null,null,0L,2,0L)),time),pages.cart(7));
    }

    @ParameterizedTest @ValueSource(booleans={false,true})
    void given__an_absent_or_present_empty_cart__when__mapping__then__preserve_null_204_versus_a_nonnull_empty_cart(boolean present) {
        var store=mock(StoreMemberReads.class);when(store.cart(7)).thenReturn(present?Optional.of(new StoreMemberReads.Cart(8L,7L,List.of(),null)):Optional.empty());
        var result=new ModuleStoreMyPage(id->null,new Read(new ArrayList<>()),store).cart(7);
        if (present) assertEquals(new StoreMyPage.Cart(8L,7L,List.of(),null),result);else assertNull(result);
    }

    @Test
    void given__configured_store_feign_array_sort_and_page_metadata__when__mapping__then__keep_array_branch_orders_and_pageimpl_total_adjustment() throws Exception {
        var rich=List.of(new StoreMemberReads.Order("sku.product.name",true,true));var store=mock(StoreMemberReads.class);
        var item=new StoreMemberReads.Wishlist(1L,2L,"sku",3L,null,null,null,null,4L,Map.of(),null,null,null);
        when(store.wishlists(7,new StoreMemberReads.Request(0,20,SORT))).thenReturn(new StoreMemberReads.Page<>(List.of(item),2,7,14,rich));
        var pages=new ModuleStoreMyPage(id->null,new Read(new ArrayList<>()),store);var page=pages.wishlists(7,DEFAULT);
        assertEquals(2,page.number());assertEquals(7,page.size());assertEquals(15,page.totalElements());
        assertEquals(List.of(new StoreMyPage.Order("sku.product.name",true,true,"NATIVE")),page.sort());
        when(store.wishlists(7,new StoreMemberReads.Request(0,20,SORT))).thenReturn(new StoreMemberReads.Page<>(List.of(item),2,7,2147483650L,rich));
        assertEquals(2147483650L,pages.wishlists(7,DEFAULT).totalElements());
    }

    static Stream<Arguments> requests() {
        return Stream.of(new StoreMyPage.Request(0,20,"createdAt,desc"),new StoreMyPage.Request(3,7,"id,desc,createdAt,asc"),
                new StoreMyPage.Request(0,20,"sku.product.name,DESC,ignorecase"),new StoreMyPage.Request(0,20,"...,,id,aSc"),
                new StoreMyPage.Request(0,20,"."),new StoreMyPage.Request(0,20,""),new StoreMyPage.Request(-3,-2,null),
                new StoreMyPage.Request(7,9000,"id,asc")).map(Arguments::of);
    }
    @ParameterizedTest @MethodSource("requests")
    void given__the_existing_single_flattened_sort_parameter__when__converting__then__match_actual_store_spring_pageable_including_lost_direction_boundaries(StoreMyPage.Request source) throws Exception {
        var web=mock(NativeWebRequest.class);when(web.getParameter("page")).thenReturn(Integer.toString(source.page()));when(web.getParameter("size")).thenReturn(Integer.toString(source.size()));
        when(web.getParameterValues("sort")).thenReturn(source.sort()==null?null:new String[]{source.sort()});
        var parameter=new MethodParameter(ModuleStoreMyPageTest.class.getDeclaredMethod("storeEndpoint",Pageable.class),0);
        Pageable expected=new PageableHandlerMethodArgumentResolver().resolveArgument(parameter,null,web,null);
        var store=mock(StoreMemberReads.class);when(store.orders(anyLong(),any())).thenReturn(new StoreMemberReads.Page<>(List.of(),0,20,0,List.of()));
        new ModuleStoreMyPage(id->null,new Read(new ArrayList<>()),store).orders(7,source);
        var orders=expected.getSort().stream().map(order->new StoreMemberReads.Order(order.getProperty(),order.isAscending(),order.isIgnoreCase())).toList();
        verify(store).orders(7,new StoreMemberReads.Request(expected.getPageNumber(),expected.getPageSize(),orders));
        if ("id,desc,createdAt,asc".equals(source.sort())) assertEquals(List.of(new StoreMemberReads.Order("id",true,false),new StoreMemberReads.Order("desc",true,false),new StoreMemberReads.Order("createdAt",true,false)),orders);
    }

    @ParameterizedTest @ValueSource(strings={"null-page","null-cart-items"})
    void given__malformed_module_data__when__mapping__then__fail_instead_of_fabricating_empty_state(String defect) {
        var store=mock(StoreMemberReads.class);when(store.cart(7)).thenReturn(Optional.of(new StoreMemberReads.Cart(8L,7L,null,null)));
        var pages=new ModuleStoreMyPage(id->null,new Read(new ArrayList<>()),store);
        assertThrows(IllegalStateException.class,()->{ if (defect.equals("null-page")) pages.orders(7,DEFAULT);else pages.cart(7); });
    }

    private void storeEndpoint(@PageableDefault(size=20,sort="createdAt",direction=Sort.Direction.DESC) Pageable pageable) { }
    private static Object call(ModuleStoreMyPage pages,String operation) { return switch(operation) { case "wishlists"->pages.wishlists(7,DEFAULT);case "orders"->pages.orders(7,DEFAULT);default->pages.cart(7); }; }
    private static Object empty(String operation) { return operation.equals("cart")?Optional.empty():new StoreMemberReads.Page<>(List.of(),0,20,0,List.of()); }
    private static void storeAnswer(StoreMemberReads store,String operation,java.util.function.Supplier<Object> answer) {
        switch(operation) { case "wishlists"->when(store.wishlists(anyLong(),any())).thenAnswer(call->answer.get());case "orders"->when(store.orders(anyLong(),any())).thenAnswer(call->answer.get());default->when(store.cart(anyLong())).thenAnswer(call->answer.get()); }
    }
    private static final class Read implements TransactionOperations {
        final List<String> events;boolean active;String phase;RuntimeException failure;
        Read(List<String> events) { this.events=events; }
        @Override public <T> T execute(TransactionCallback<T> callback) {
            events.add("begin");if ("begin".equals(phase)) throw failure;active=true;
            try { T value=callback.doInTransaction(new SimpleTransactionStatus());if ("commit".equals(phase)) throw failure;events.add("commit");return value; }
            finally { active=false;events.add("end"); }
        }
    }
}
