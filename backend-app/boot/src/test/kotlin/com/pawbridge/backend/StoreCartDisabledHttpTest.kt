package com.pawbridge.backend

import com.ninjasquad.springmockk.MockkBean
import com.pawbridge.backend.boot.configuration.ReadSecurityConfiguration
import com.pawbridge.backend.store.internal.cart.application.Carts
import com.pawbridge.backend.store.internal.cart.web.CartController
import io.mockk.confirmVerified
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.test.context.ContextConfiguration
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

@WebMvcTest(excludeAutoConfiguration = [UserDetailsServiceAutoConfiguration::class])
@ContextConfiguration(
    classes = [CartController::class, ReadSecurityConfiguration::class, F3TestInputs.SecurityInputs::class],
)
class StoreCartDisabledHttpTest {
    @Autowired
    lateinit var mvc: MockMvc

    @MockkBean
    lateinit var carts: Carts

    @ParameterizedTest
    @CsvSource(
        "GET,/api/v1/carts",
        "POST,/api/v1/carts/items",
        "PATCH,/api/v1/carts/items/7",
        "DELETE,/api/v1/carts/items/7",
        "DELETE,/api/v1/carts",
        "POST,/api/v1/orders",
        "GET,/api/v1/orders/7",
    )
    fun `장바구니 플래그가 없을 때__인증된 회원이 장바구니 경로를 호출하면__비즈니스 전에 거부한다`(
        method: String,
        path: String,
    ) {
        // given
        val request = command(method, path)

        // when
        val result = mvc.perform(request)

        // then
        result.andExpect(status().isForbidden())
        confirmVerified(carts)
    }

    companion object {
        fun command(method: String, path: String): MockHttpServletRequestBuilder {
            return request(HttpMethod.valueOf(method), path)
                .header("Authorization", "Bearer " + F3TestInputs.token(7, "ROLE_USER"))
                .header("X-User-Id", "99")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}")
        }
    }
}

@WebMvcTest(
    properties = ["pawbridge.store-carts.enabled=on"],
    excludeAutoConfiguration = [UserDetailsServiceAutoConfiguration::class],
)
@ContextConfiguration(
    classes = [CartController::class, ReadSecurityConfiguration::class, F3TestInputs.SecurityInputs::class],
)
class StoreCartAliasDisabledHttpTest {
    @Autowired
    lateinit var mvc: MockMvc

    @MockkBean
    lateinit var carts: Carts

    @ParameterizedTest
    @CsvSource(
        "GET,/api/v1/carts",
        "POST,/api/v1/carts/items",
        "PATCH,/api/v1/carts/items/7",
        "DELETE,/api/v1/carts/items/7",
        "DELETE,/api/v1/carts",
        "POST,/api/v1/orders",
        "GET,/api/v1/orders/7",
    )
    fun `소유되지 않은 불리언 별칭일 때__인증된 회원이 경로를 호출하면__소유자 플래그를 닫은 채 거부한다`(
        method: String,
        path: String,
    ) {
        // given
        val request = StoreCartDisabledHttpTest.command(method, path)

        // when
        val result = mvc.perform(request)

        // then
        result.andExpect(status().isForbidden())
        confirmVerified(carts)
    }
}

@WebMvcTest(
    properties = ["pawbridge.store-carts.enabled=true"],
    excludeAutoConfiguration = [UserDetailsServiceAutoConfiguration::class],
)
@ContextConfiguration(
    classes = [CartController::class, ReadSecurityConfiguration::class, F3TestInputs.SecurityInputs::class],
)
class StoreCartNeighborDisabledHttpTest {
    @Autowired
    lateinit var mvc: MockMvc

    @MockkBean
    lateinit var carts: Carts

    @ParameterizedTest
    @CsvSource(
        "POST,/api/v1/carts/reset",
        "GET,/api/v1/carts/99",
        "PUT,/api/v1/carts/items/7",
        "POST,/api/v1/orders/direct",
        "POST,/api/v1/payments/confirm",
    )
    fun `장바구니 관리만 켜져 있을 때__인증된 회원이 이식되지 않은 경로를 호출하면__이웃 경로를 닫아 둔다`(
        method: String,
        path: String,
    ) {
        // given
        val request = StoreCartDisabledHttpTest.command(method, path)

        // when
        val result = mvc.perform(request)

        // then
        result.andExpect(status().isForbidden())
        confirmVerified(carts)
    }
}
