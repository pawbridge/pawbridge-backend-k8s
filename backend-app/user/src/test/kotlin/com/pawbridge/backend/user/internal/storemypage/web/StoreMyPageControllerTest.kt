package com.pawbridge.backend.user.internal.storemypage.web

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.pawbridge.backend.user.internal.profile.application.UserNotFoundException
import com.pawbridge.backend.user.internal.storemypage.application.StoreMyPage
import io.mockk.confirmVerified
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import org.springframework.web.bind.annotation.RestControllerAdvice

class StoreMyPageControllerTest {
    @ParameterizedTest
    @ValueSource(strings = ["wishlists", "orders"])
    fun `검증된 현재 회원과 페이지 정보가 있을 때__목록을 읽으면__현재 신원만 넘기고 정렬을 평탄화해 원격 페이지 응답을 다시 만든다`(
        endpoint: String,
    ) {
        // given
        val pages = mockk<StoreMyPage>()
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

        if (endpoint == "wishlists") {
            every { pages.wishlists(any(), any()) } returns
                StoreMyPage.Page(listOf(), page.number, page.size, page.totalElements, page.sort)
        } else {
            every { pages.orders(any(), any()) } returns
                StoreMyPage.Page(listOf(), page.number, page.size, page.totalElements, page.sort)
        }

        // when
        val response: Any = if (endpoint == "wishlists") {
            controller.wishlists(7L, pageable)
        } else {
            controller.orders(7L, pageable)
        }
        val json = ObjectMapper().valueToTree<JsonNode>(response)

        // then
        assertEquals(200, json.path("code").asInt())
        assertEquals("요청이 성공적으로 처리되었습니다.", json.path("message").asText())
        assertEquals(2, json.path("data").path("number").asInt())
        assertEquals(7, json.path("data").path("size").asInt())
        assertEquals(2147483650L, json.path("data").path("totalElements").longValue())
        assertEquals(11, json.path("data").size())
        if (endpoint == "wishlists") {
            verify(exactly = 1) { pages.wishlists(7L, request) }
        } else {
            verify(exactly = 1) { pages.orders(7L, request) }
        }
        confirmVerified(pages)
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `장바구니가 없거나 있을 때__읽으면__204 빈 응답 또는 200 봉투를 유지한다`(present: Boolean) {
        // given
        val pages = mockk<StoreMyPage>()
        val cart = StoreMyPage.Cart(8L, 7L, listOf(), null)

        every { pages.cart(any()) } returns if (present) cart else null

        // when
        val response = StoreMyPageController(pages).cart(7L)

        // then
        assertEquals(if (present) 200 else 204, response.statusCode.value())
        if (present) {
            assertEquals(cart, response.body!!.data)
            assertEquals(200, response.body!!.code)
        } else {
            assertNull(response.body)
        }
        verify(exactly = 1) { pages.cart(7L) }
        confirmVerified(pages)
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `회원이 없거나 원격 호출이 실패했을 때__범위 지정 어드바이스가 응답하면__원래 400 또는 원격 메시지 없는 고정 500을 유지한다`(
        missing: Boolean,
    ) {
        // given
        val handler = StoreMyPageExceptionHandler()

        // when
        val response = if (missing) {
            handler.missing(UserNotFoundException())
        } else {
            handler.unavailable(IllegalStateException("synthetic-secret-store-body"))
        }

        // then
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
