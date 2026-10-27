package com.pawbridge.backend

import com.ninjasquad.springmockk.MockkBean
import com.pawbridge.backend.boot.configuration.ReadSecurityConfiguration
import com.pawbridge.backend.user.internal.storemypage.application.StoreMyPage
import com.pawbridge.backend.user.internal.storemypage.web.StoreMyPageController
import com.pawbridge.backend.user.internal.storemypage.web.StoreMyPageExceptionHandler
import io.mockk.confirmVerified
import io.mockk.every
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.test.context.ContextConfiguration
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.LocalDateTime

@WebMvcTest(
    properties = ["pawbridge.user-store-mypage.enabled=true"],
    excludeAutoConfiguration = [UserDetailsServiceAutoConfiguration::class],
)
@ContextConfiguration(
    classes = [
        StoreMyPageController::class,
        StoreMyPageExceptionHandler::class,
        ReadSecurityConfiguration::class,
        F3TestInputs.SecurityInputs::class,
    ],
)
class UserStoreMyPageHttpTest {
    @Autowired
    lateinit var mvc: MockMvc

    @MockkBean
    lateinit var myPage: StoreMyPage

    @Test
    fun `검증된 회원과 위조 신원이 주어졌을 때__세 경로를 모두 조회하면__JWT 사용자만 쓰고 빈 장바구니 204를 유지한다`() {
        // given
        every { myPage.wishlists(any(), any()) } returns StoreMyPage.Page(listOf(), 0, 20, 0, listOf())
        every { myPage.orders(any(), any()) } returns StoreMyPage.Page(listOf(), 0, 20, 0, listOf())
        every { myPage.cart(any()) } returns null
        val token = "Bearer " + F3TestInputs.token(7, "ROLE_USER")

        // when
        val pages = listOf("wishlists", "orders").map { path ->
            mvc.perform(get(ROOT + path).header("Authorization", token).header("X-User-Id", "999"))
        }
        val cart = mvc.perform(get(ROOT + "cart").header("Authorization", token).header("X-User-Id", "999"))

        // then
        pages.forEach { page ->
            page
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(0))
                .andExpect(header().doesNotExist("Set-Cookie"))
        }
        cart
            .andExpect(status().isNoContent())
            .andExpect(content().string(""))
        val defaults = StoreMyPage.Request(0, 20, "createdAt,desc")
        verify(exactly = 1) { myPage.wishlists(7, defaults) }
        verify(exactly = 1) { myPage.orders(7, defaults) }
        verify(exactly = 1) { myPage.cart(7) }
        confirmVerified(myPage)
        assertThat(SecurityContextHolder.getContext().authentication).isNull()
    }

    @Test
    fun `찜한 SKU가 있을 때__찜 목록을 조회하면__화면이 쓰는 SKU 가격 옵션을 함께 준다`() {
        // given
        val time = LocalDateTime.of(2026, 10, 27, 20, 30)
        val wishlist = StoreMyPage.Wishlist(
            1L, 10L, "PADDING-RED-L", 5L, "패딩", null, null, "ACTIVE", 35000L, mapOf("Color" to "Red"), 2L, "의류", time,
        )
        every { myPage.wishlists(any(), any()) } returns StoreMyPage.Page(listOf(wishlist), 0, 20, 1, listOf())
        val token = "Bearer " + F3TestInputs.token(7, "ROLE_USER")

        // when
        val page = mvc.perform(get(ROOT + "wishlists").header("Authorization", token))

        // then
        page
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.content[0].wishlistId").value(1))
            .andExpect(jsonPath("$.data.content[0].skuId").value(10))
            .andExpect(jsonPath("$.data.content[0].skuCode").value("PADDING-RED-L"))
            .andExpect(jsonPath("$.data.content[0].price").value(35000))
            .andExpect(jsonPath("$.data.content[0].options.Color").value("Red"))
            .andExpect(jsonPath("$.data.totalPages").value(1))
    }

    companion object {
        private const val ROOT = "/api/v1/users/me/"
    }
}
