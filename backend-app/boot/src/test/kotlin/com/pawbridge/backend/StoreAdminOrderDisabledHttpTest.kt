package com.pawbridge.backend

import com.ninjasquad.springmockk.MockkBean
import com.pawbridge.backend.boot.configuration.ReadSecurityConfiguration
import com.pawbridge.backend.store.internal.orderadministration.application.AdminOrders
import com.pawbridge.backend.store.internal.orderadministration.web.AdminOrderController
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
    classes = [AdminOrderController::class, ReadSecurityConfiguration::class, F3TestInputs.SecurityInputs::class],
)
class StoreAdminOrderDisabledHttpTest {
    @Autowired
    lateinit var mvc: MockMvc

    @MockkBean
    lateinit var orders: AdminOrders

    @ParameterizedTest
    @CsvSource(
        "GET,/api/v1/admin/orders",
        "GET,/api/v1/admin/orders/7",
        "PATCH,/api/v1/admin/orders/7/delivery-status",
        "PATCH,/api/v1/admin/orders/7/status",
    )
    fun `관리자 기능이 꺼져 있을 때__서명된 관리자가 경로를 호출하면__비즈니스 전에 거부한다`(
        method: String,
        path: String,
    ) {
        // given
        val request = command(method, path)

        // when
        val result = mvc.perform(request)

        // then
        result.andExpect(status().isForbidden())
        confirmVerified(orders)
    }

    companion object {
        fun command(method: String, path: String): MockHttpServletRequestBuilder {
            return request(HttpMethod.valueOf(method), path)
                .header("Authorization", "Bearer " + F3TestInputs.token(7, "ROLE_ADMIN"))
                .header("X-User-Id", "99")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}")
        }
    }
}

@WebMvcTest(
    properties = ["pawbridge.store-order-administration.enabled=on"],
    excludeAutoConfiguration = [UserDetailsServiceAutoConfiguration::class],
)
@ContextConfiguration(
    classes = [AdminOrderController::class, ReadSecurityConfiguration::class, F3TestInputs.SecurityInputs::class],
)
class StoreAdminOrderAliasDisabledHttpTest {
    @Autowired
    lateinit var mvc: MockMvc

    @MockkBean
    lateinit var orders: AdminOrders

    @ParameterizedTest
    @CsvSource(
        "GET,/api/v1/admin/orders",
        "GET,/api/v1/admin/orders/7",
        "PATCH,/api/v1/admin/orders/7/delivery-status",
        "PATCH,/api/v1/admin/orders/7/status",
    )
    fun `불리언 별칭으로 켰을 때__서명된 관리자가 경로를 호출하면__소유자 플래그를 닫은 채 거부한다`(
        method: String,
        path: String,
    ) {
        // given
        val request = StoreAdminOrderDisabledHttpTest.command(method, path)

        // when
        val result = mvc.perform(request)

        // then
        result.andExpect(status().isForbidden())
        confirmVerified(orders)
    }
}

@WebMvcTest(
    properties = ["pawbridge.store-order-administration.enabled=true"],
    excludeAutoConfiguration = [UserDetailsServiceAutoConfiguration::class],
)
@ContextConfiguration(
    classes = [AdminOrderController::class, ReadSecurityConfiguration::class, F3TestInputs.SecurityInputs::class],
)
class StoreAdminOrderNeighborDisabledHttpTest {
    @Autowired
    lateinit var mvc: MockMvc

    @MockkBean
    lateinit var orders: AdminOrders

    @ParameterizedTest
    @CsvSource(
        "PUT,/api/v1/orders/7/status",
        "PUT,/api/v1/orders/7/cancel",
        "GET,/api/v1/orders/uuid/example",
        "POST,/api/v1/payments/confirm",
        "GET,/api/v1/orders/7",
        "PUT,/api/v1/admin/orders/7/status",
        "POST,/api/v1/admin/orders/7/status",
        "PATCH,/api/v1/admin/orders/7/status/",
        "PATCH,/api/v1/admin/orders/7/status/extra",
    )
    fun `관리 기능만 켜져 있을 때__이식되지 않은 주문 경로를 호출하면__비즈니스 전에 거부한다`(
        method: String,
        path: String,
    ) {
        // given
        val request = StoreAdminOrderDisabledHttpTest.command(method, path)

        // when
        val result = mvc.perform(request)

        // then
        result.andExpect(status().isForbidden())
        confirmVerified(orders)
    }
}
