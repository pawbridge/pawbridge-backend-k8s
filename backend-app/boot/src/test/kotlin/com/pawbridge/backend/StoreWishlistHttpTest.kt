package com.pawbridge.backend

import com.ninjasquad.springmockk.MockkBean
import com.pawbridge.backend.boot.configuration.ReadSecurityConfiguration
import com.pawbridge.backend.store.internal.wishlist.application.WishlistCommands
import com.pawbridge.backend.store.internal.wishlist.web.WishlistCommandController
import com.pawbridge.backend.store.internal.wishlist.web.WishlistCommandExceptionHandler
import io.mockk.confirmVerified
import io.mockk.every
import io.mockk.justRun
import io.mockk.verify
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.test.context.ContextConfiguration
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

@WebMvcTest(
    properties = ["pawbridge.store-wishlist.enabled=true"],
    excludeAutoConfiguration = [UserDetailsServiceAutoConfiguration::class],
)
@ContextConfiguration(
    classes = [
        WishlistCommandController::class,
        WishlistCommandExceptionHandler::class,
        ReadSecurityConfiguration::class,
        F3TestInputs.SecurityInputs::class,
    ],
)
class StoreWishlistHttpTest {
    @Autowired
    lateinit var mvc: MockMvc

    @MockkBean
    lateinit var commands: WishlistCommands

    @ParameterizedTest
    @CsvSource("POST,ROLE_USER", "POST,ROLE_ADMIN", "DELETE,ROLE_USER", "DELETE,ROLE_ADMIN")
    fun `플래그가 켜졌고 검증된 회원일 때__정확한 경로로 명령하면__위조 신원 대신 기존 JWT 주체를 쓴다`(
        method: String,
        role: String,
    ) {
        // given
        val request = request(HttpMethod.valueOf(method), "/api/v1/wishlists")
            .header("Authorization", "Bearer " + F3TestInputs.token(7, role))
            .header("X-User-Id", "99")
            .header("X-User-Role", "ROLE_ADMIN")
            .param("userId", "99")
            .param("skuId", "80")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"skuId\":80,\"userId\":99}")

        every { commands.add(any(), any()) } returns
            WishlistCommands.Added(33L, 7L, 80L, "SKU", 9L, "item", null, 0L, mutableMapOf(), null)
        justRun { commands.removeByMemberAndSku(any(), any()) }

        // when
        val result = mvc.perform(request)

        // then
        result
            .andExpect(status().`is`(if (method == "POST") 201 else 204))
            .andExpect(header().doesNotExist("Set-Cookie"))
        if (method == "POST") {
            verify(exactly = 1) { commands.add(7, 80L) }
        } else {
            verify(exactly = 1) { commands.removeByMemberAndSku(7, 80) }
        }
        confirmVerified(commands)
    }

    @ParameterizedTest
    @CsvSource("POST,missing", "POST,invalid", "DELETE,missing", "DELETE,invalid")
    fun `플래그는 켜졌지만 JWT가 없거나 잘못됐을 때__사용자 헤더를 위조하면__명령 전에 기존 필터가 401을 돌려준다`(
        method: String,
        token: String,
    ) {
        // given
        val request = request(HttpMethod.valueOf(method), "/api/v1/wishlists")
            .param("skuId", "80")
            .header("X-User-Id", "99")
            .header("X-User-Role", "ROLE_ADMIN")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"skuId\":80,\"userId\":99}")
        if (token == "invalid") {
            request.header("Authorization", "Bearer invalid-token")
        }

        // when
        val result = mvc.perform(request)

        // then
        result.andExpect(status().isUnauthorized())
        confirmVerified(commands)
    }

    @ParameterizedTest
    @ValueSource(strings = ["ROLE_USER", "ROLE_ADMIN"])
    fun `검증된 회원일 때__위시리스트 ID를 삭제하면__주체를 쓰고 204를 돌려준다`(role: String) {
        // given
        val request = delete("/api/v1/wishlists/33")
            .header("Authorization", "Bearer " + F3TestInputs.token(7, role))
            .header("X-User-Id", "99")
            .header("X-User-Role", "ROLE_ADMIN")
            .param("userId", "99")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"userId\":99}")

        justRun { commands.removeById(any(), any()) }

        // when
        val result = mvc.perform(request)

        // then
        result
            .andExpect(status().isNoContent())
            .andExpect(header().doesNotExist("Set-Cookie"))
        verify(exactly = 1) { commands.removeById(7, 33) }
        confirmVerified(commands)
    }

    @ParameterizedTest
    @ValueSource(strings = ["missing", "invalid"])
    fun `유효한 JWT가 없을 때__위조 헤더로 위시리스트 ID를 삭제하면__명령 전에 거부한다`(token: String) {
        // given
        val request = delete("/api/v1/wishlists/33").header("X-User-Id", "99").header("X-User-Role", "ROLE_ADMIN")
        if (token == "invalid") {
            request.header("Authorization", "Bearer invalid-token")
        }

        // when
        val result = mvc.perform(request)

        // then
        result.andExpect(status().isUnauthorized())
        confirmVerified(commands)
    }

    @ParameterizedTest
    @CsvSource(
        "GET,/api/v1/wishlists", "PUT,/api/v1/wishlists", "PATCH,/api/v1/wishlists", "POST,/api/v1/wishlists/33",
        "GET,/api/v1/wishlists/33", "PUT,/api/v1/wishlists/33", "PATCH,/api/v1/wishlists/33",
        "DELETE,/api/v1/wishlists/33/extra", "DELETE,/api/wishlists/33", "POST,/api/wishlists",
    )
    fun `검증된 관리자일 때__이식되지 않은 이웃 경로를 시도하면__명령 없이 거부한다`(
        method: String,
        path: String,
    ) {
        // given
        val request = request(HttpMethod.valueOf(method), path)
            .header("Authorization", "Bearer " + F3TestInputs.token(7, "ROLE_ADMIN"))

        // when
        val result = mvc.perform(request)

        // then
        result.andExpect(status().isForbidden())
        confirmVerified(commands)
    }
}

@WebMvcTest(excludeAutoConfiguration = [UserDetailsServiceAutoConfiguration::class])
@ContextConfiguration(
    classes = [
        WishlistCommandController::class,
        WishlistCommandExceptionHandler::class,
        ReadSecurityConfiguration::class,
        F3TestInputs.SecurityInputs::class,
    ],
)
class StoreWishlistDisabledHttpTest {
    @Autowired
    lateinit var mvc: MockMvc

    @MockkBean
    lateinit var commands: WishlistCommands

    @Test
    fun `명시적 플래그가 없을 때__위시리스트 ID를 삭제하면__애플리케이션 전에 거부한다`() {
        // given
        val request = delete("/api/v1/wishlists/33")
            .header("Authorization", "Bearer " + F3TestInputs.token(7, "ROLE_ADMIN"))

        // when
        val result = mvc.perform(request)

        // then
        result.andExpect(status().isForbidden())
        confirmVerified(commands)
    }

    @ParameterizedTest
    @ValueSource(strings = ["POST", "DELETE"])
    fun `명시적 플래그가 없을 때__검증된 관리자가 정확한 경로로 명령하면__애플리케이션 전에 거부한다`(
        method: String,
    ) {
        // given
        val request = request(HttpMethod.valueOf(method), "/api/v1/wishlists")
            .param("skuId", "80")
            .header("Authorization", "Bearer " + F3TestInputs.token(7, "ROLE_ADMIN"))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"skuId\":80}")

        // when
        val result = mvc.perform(request)

        // then
        result.andExpect(status().isForbidden())
        confirmVerified(commands)
    }
}
