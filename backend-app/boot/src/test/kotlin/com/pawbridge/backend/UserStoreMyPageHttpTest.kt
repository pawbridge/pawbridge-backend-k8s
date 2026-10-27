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

    companion object {
        private const val ROOT = "/api/v1/users/me/"
    }
}
