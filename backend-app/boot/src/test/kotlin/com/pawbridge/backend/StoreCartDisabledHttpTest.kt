package com.pawbridge.backend

import com.pawbridge.backend.boot.configuration.ReadSecurityConfiguration
import com.pawbridge.backend.store.internal.cart.application.Carts
import com.pawbridge.backend.store.internal.cart.web.CartController
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
    classes = [CartController::class, ReadSecurityConfiguration::class, F3TestInputs.SecurityInputs::class],
)
class StoreCartDisabledHttpTest {
    @Autowired
    lateinit var mvc: MockMvc

    @MockitoBean
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
    fun given__no_cart_flag__when__verified_member_uses_a_cart_route__then__deny_before_business(
        method: String,
        path: String,
    ) {
        mvc.perform(command(method, path)).andExpect(status().isForbidden())
        verifyNoInteractions(carts)
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

    @MockitoBean
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
    fun given__unowned_boolean_alias__when__verified_member_uses_a_route__then__keep_the_owner_flag_closed(
        method: String,
        path: String,
    ) {
        mvc.perform(StoreCartDisabledHttpTest.command(method, path)).andExpect(status().isForbidden())
        verifyNoInteractions(carts)
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

    @MockitoBean
    lateinit var carts: Carts

    @ParameterizedTest
    @CsvSource(
        "POST,/api/v1/carts/reset",
        "GET,/api/v1/carts/99",
        "PUT,/api/v1/carts/items/7",
        "POST,/api/v1/orders/direct",
        "POST,/api/v1/payments/confirm",
    )
    fun given__enabled_cart_management_only__when__verified_member_uses_an_unported_route__then__keep_neighbor_routes_closed(
        method: String,
        path: String,
    ) {
        mvc.perform(StoreCartDisabledHttpTest.command(method, path)).andExpect(status().isForbidden())
        verifyNoInteractions(carts)
    }
}
