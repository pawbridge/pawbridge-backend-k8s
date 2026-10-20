package com.pawbridge.backend.user.internal.storemypage.web;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pawbridge.backend.user.internal.profile.application.UserNotFoundException;
import com.pawbridge.backend.user.internal.storemypage.application.StoreMyPage;
import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.web.bind.annotation.RestControllerAdvice;

class StoreMyPageControllerTest {
    @ParameterizedTest
    @ValueSource(strings = {"wishlists", "orders"})
    void given__a_verified_current_member_and_pageable__when__reading_lists__then__forward_only_the_current_identity_flatten_sort_and_rebuild_remote_page_wire(String endpoint) throws Exception {
        var pages = mock(StoreMyPage.class);
        var page = new StoreMyPage.Page<>(List.of(), 2, 7, 2147483650L, List.of(new StoreMyPage.Order("createdAt", false, false, "NATIVE")));
        var request = new StoreMyPage.Request(1, 20, "name,asc,createdAt,desc");
        var pageable = PageRequest.of(1, 20, Sort.by(Sort.Order.asc("name"), Sort.Order.desc("createdAt")));
        var controller = new StoreMyPageController(pages);
        Object response;
        if (endpoint.equals("wishlists")) {
            when(pages.wishlists(7, request)).thenReturn(new StoreMyPage.Page<>(List.of(), page.number(), page.size(), page.totalElements(), page.sort()));
            response = controller.wishlists(7L, pageable); verify(pages).wishlists(7, request);
        } else {
            when(pages.orders(7, request)).thenReturn(new StoreMyPage.Page<>(List.of(), page.number(), page.size(), page.totalElements(), page.sort()));
            response = controller.orders(7L, pageable); verify(pages).orders(7, request);
        }
        var json = new ObjectMapper().valueToTree(response);
        assertEquals(200, json.path("code").asInt());
        assertEquals("요청이 성공적으로 처리되었습니다.", json.path("message").asText());
        assertEquals(2, json.path("data").path("number").asInt());
        assertEquals(7, json.path("data").path("size").asInt());
        assertEquals(2147483650L, json.path("data").path("totalElements").longValue());
        assertEquals(11, json.path("data").size()); verifyNoMoreInteractions(pages);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void given__an_absent_or_existing_cart__when__reading__then__keep_204_empty_or_200_envelope(boolean present) {
        var pages = mock(StoreMyPage.class);
        var cart = new StoreMyPage.Cart(8L, 7L, List.of(), null);
        when(pages.cart(7)).thenReturn(present ? cart : null);
        var response = new StoreMyPageController(pages).cart(7L);
        assertEquals(present ? 200 : 204, response.getStatusCode().value());
        if (present) { assertEquals(cart, response.getBody().data()); assertEquals(200, response.getBody().code()); }
        else assertNull(response.getBody());
        verify(pages).cart(7); verifyNoMoreInteractions(pages);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void given__a_missing_user_or_remote_failure__when__scoped_advice_responds__then__keep_source_400_or_fixed_500_without_remote_messages(boolean missing) throws Exception {
        var handler = new StoreMyPageExceptionHandler();
        var response = missing ? handler.missing(new UserNotFoundException())
                : handler.unavailable(new IllegalStateException("synthetic-secret-store-body"));
        assertEquals(missing ? 400 : 500, response.getStatusCode().value());
        assertEquals(missing ? "존재하지 않는 회원입니다." : "서버 에러!", response.getBody().message());
        assertNull(response.getBody().data());
        assertFalse(new ObjectMapper().writeValueAsString(response.getBody()).contains("synthetic-secret-store-body"));
        assertArrayEquals(new Class<?>[] {StoreMyPageController.class},
                StoreMyPageExceptionHandler.class.getAnnotation(RestControllerAdvice.class).assignableTypes());
    }
}
