package com.pawbridge.backend

import com.pawbridge.backend.boot.configuration.ReadSecurityConfiguration
import com.pawbridge.backend.store.internal.directorder.application.DirectOrders
import com.pawbridge.backend.store.internal.directorder.web.DirectOrderController
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.mockito.Mockito.verifyNoInteractions
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.test.context.ContextConfiguration
import org.springframework.test.context.bean.override.mockito.MockitoBean
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

    @MockitoBean
    lateinit var orders: DirectOrders

    @ParameterizedTest
    @CsvSource("POST,/api/v1/orders/direct", "GET,/api/v1/orders", "GET,/api/v1/orders/7")
    fun given__no_order_flag__when__verified_member_uses_an_order_route__then__deny_before_business(
        method: String,
        path: String,
    ) {
        mvc.perform(command(method, path)).andExpect(status().isForbidden())
        verifyNoInteractions(orders)
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

    @MockitoBean
    lateinit var orders: DirectOrders

    @ParameterizedTest
    @CsvSource("POST,/api/v1/orders/direct", "GET,/api/v1/orders", "GET,/api/v1/orders/7")
    fun given__unowned_boolean_alias__when__verified_member_uses_a_route__then__keep_the_owner_flag_closed(
        method: String,
        path: String,
    ) {
        mvc.perform(DirectOrderDisabledHttpTest.command(method, path)).andExpect(status().isForbidden())
        verifyNoInteractions(orders)
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

    @MockitoBean
    lateinit var orders: DirectOrders

    @ParameterizedTest
    @CsvSource("POST,/api/v1/orders", "GET,/api/v1/orders/uuid/abc", "PUT,/api/v1/orders/7/status")
    fun given__enabled_direct_order_only__when__verified_member_uses_an_unported_route__then__keep_neighbor_routes_closed(
        method: String,
        path: String,
    ) {
        mvc.perform(DirectOrderDisabledHttpTest.command(method, path)).andExpect(status().isForbidden())
        verifyNoInteractions(orders)
    }
}
