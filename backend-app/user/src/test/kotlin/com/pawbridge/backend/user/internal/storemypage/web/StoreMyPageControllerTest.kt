package com.pawbridge.backend.user.internal.storemypage.web

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.pawbridge.backend.user.internal.profile.application.UserNotFoundException
import com.pawbridge.backend.user.internal.storemypage.application.StoreMyPage
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoMoreInteractions
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import org.springframework.web.bind.annotation.RestControllerAdvice

class StoreMyPageControllerTest {
    @ParameterizedTest
    @ValueSource(strings = ["wishlists", "orders"])
    fun given__a_verified_current_member_and_pageable__when__reading_lists__then__forward_only_the_current_identity_flatten_sort_and_rebuild_remote_page_wire(
        endpoint: String,
    ) {
        val pages = mock(StoreMyPage::class.java)
        val page = StoreMyPage.Page<Any>(
            listOf(),
            2,
            7,
            2147483650L,
            listOf(StoreMyPage.Order("createdAt", false, false, "NATIVE")),
        )
        val request = StoreMyPage.Request(1, 20, "name,asc,createdAt,desc")
        val pageable = PageRequest.of(1, 20, Sort.by(Sort.Order.asc("name"), Sort.Order.desc("createdAt")))
        val controller = StoreMyPageController(pages)
        val response: Any
        if (endpoint == "wishlists") {
            `when`(pages.wishlists(7L, request))
                .thenReturn(StoreMyPage.Page(listOf(), page.number, page.size, page.totalElements, page.sort))
            response = controller.wishlists(7L, pageable)
            verify(pages).wishlists(7L, request)
        } else {
            `when`(pages.orders(7L, request))
                .thenReturn(StoreMyPage.Page(listOf(), page.number, page.size, page.totalElements, page.sort))
            response = controller.orders(7L, pageable)
            verify(pages).orders(7L, request)
        }

        val json = ObjectMapper().valueToTree<JsonNode>(response)

        assertEquals(200, json.path("code").asInt())
        assertEquals("요청이 성공적으로 처리되었습니다.", json.path("message").asText())
        assertEquals(2, json.path("data").path("number").asInt())
        assertEquals(7, json.path("data").path("size").asInt())
        assertEquals(2147483650L, json.path("data").path("totalElements").longValue())
        assertEquals(11, json.path("data").size())
        verifyNoMoreInteractions(pages)
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun given__an_absent_or_existing_cart__when__reading__then__keep_204_empty_or_200_envelope(present: Boolean) {
        val pages = mock(StoreMyPage::class.java)
        val cart = StoreMyPage.Cart(8L, 7L, listOf(), null)
        `when`(pages.cart(7L)).thenReturn(if (present) cart else null)

        val response = StoreMyPageController(pages).cart(7L)

        assertEquals(if (present) 200 else 204, response.statusCode.value())
        if (present) {
            assertEquals(cart, response.body!!.data)
            assertEquals(200, response.body!!.code)
        } else {
            assertNull(response.body)
        }
        verify(pages).cart(7L)
        verifyNoMoreInteractions(pages)
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun given__a_missing_user_or_remote_failure__when__scoped_advice_responds__then__keep_source_400_or_fixed_500_without_remote_messages(
        missing: Boolean,
    ) {
        val handler = StoreMyPageExceptionHandler()
        val response = if (missing) {
            handler.missing(UserNotFoundException())
        } else {
            handler.unavailable(IllegalStateException("synthetic-secret-store-body"))
        }

        assertEquals(if (missing) 400 else 500, response.statusCode.value())
        assertEquals(if (missing) "존재하지 않는 회원입니다." else "서버 에러!", response.body!!.message)
        assertNull(response.body!!.data)
        assertFalse(ObjectMapper().writeValueAsString(response.body).contains("synthetic-secret-store-body"))
        assertArrayEquals(
            arrayOf<Class<*>>(StoreMyPageController::class.java),
            StoreMyPageExceptionHandler::class.java.getAnnotation(RestControllerAdvice::class.java).assignableTypes.map { type -> type.java }.toTypedArray(),
        )
    }
}
