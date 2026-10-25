package com.pawbridge.backend

import com.ninjasquad.springmockk.MockkBean
import com.pawbridge.backend.boot.configuration.ReadSecurityConfiguration
import com.pawbridge.backend.store.internal.directorder.application.DirectOrders
import com.pawbridge.backend.store.internal.directorder.web.DirectOrderController
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
    classes = [DirectOrderController::class, ReadSecurityConfiguration::class, F3TestInputs.SecurityInputs::class],
)
class DirectOrderDisabledHttpTest {
    @Autowired
    lateinit var mvc: MockMvc

    @MockkBean
    lateinit var orders: DirectOrders

    @ParameterizedTest
    @CsvSource("POST,/api/v1/orders/direct", "GET,/api/v1/orders", "GET,/api/v1/orders/7")
    fun `주문 플래그가 없을 때__검증된 회원이 주문 경로를 쓰면__업무 호출 전에 거부한다`(
        method: String,
        path: String,
    ) {
        // given

        // when
        val result = mvc.perform(command(method, path))

        // then
        result.andExpect(status().isForbidden())
        confirmVerified(orders)
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
    properties = ["pawbridge.store-direct-orders.enabled=on", "pawbridge.store-carts.enabled=yes"],
    excludeAutoConfiguration = [UserDetailsServiceAutoConfiguration::class],
)
@ContextConfiguration(
    classes = [DirectOrderController::class, ReadSecurityConfiguration::class, F3TestInputs.SecurityInputs::class],
)
class DirectOrderAliasDisabledHttpTest {
    @Autowired
    lateinit var mvc: MockMvc

    @MockkBean
    lateinit var orders: DirectOrders

    @ParameterizedTest
    @CsvSource("POST,/api/v1/orders/direct", "GET,/api/v1/orders", "GET,/api/v1/orders/7")
    fun `소유자 없는 불리언 별칭일 때__검증된 회원이 경로를 쓰면__소유자 플래그를 닫아 둔다`(
        method: String,
        path: String,
    ) {
        // given

        // when
        val result = mvc.perform(DirectOrderDisabledHttpTest.command(method, path))

        // then
        result.andExpect(status().isForbidden())
        confirmVerified(orders)
    }
}

@WebMvcTest(
    properties = ["pawbridge.store-direct-orders.enabled=true"],
    excludeAutoConfiguration = [UserDetailsServiceAutoConfiguration::class],
)
@ContextConfiguration(
    classes = [DirectOrderController::class, ReadSecurityConfiguration::class, F3TestInputs.SecurityInputs::class],
)
class DirectOrderNeighborDisabledHttpTest {
    @Autowired
    lateinit var mvc: MockMvc

    @MockkBean
    lateinit var orders: DirectOrders

    @ParameterizedTest
    @CsvSource("POST,/api/v1/orders", "GET,/api/v1/orders/uuid/abc", "PUT,/api/v1/orders/7/status")
    fun `직접 주문만 켜졌을 때__검증된 회원이 이식되지 않은 경로를 쓰면__이웃 경로를 닫아 둔다`(
        method: String,
        path: String,
    ) {
        // given

        // when
        val result = mvc.perform(DirectOrderDisabledHttpTest.command(method, path))

        // then
        result.andExpect(status().isForbidden())
        confirmVerified(orders)
    }
}
