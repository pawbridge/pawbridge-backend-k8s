package com.pawbridge.backend

import com.pawbridge.backend.boot.configuration.ReadSecurityConfiguration
import com.pawbridge.backend.store.api.StoreMemberReads
import com.pawbridge.backend.store.internal.directorder.application.DirectOrders
import com.pawbridge.backend.store.internal.directorder.web.OrderReadController
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
import org.mockito.Mockito.`when` as whenever
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.verifyNoMoreInteractions
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.http.HttpMethod
import org.springframework.test.context.ContextConfiguration
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.LocalDateTime

@WebMvcTest(
    properties = ["pawbridge.store-direct-orders.enabled=true"],
    excludeAutoConfiguration = [UserDetailsServiceAutoConfiguration::class],
)
@ContextConfiguration(
    classes = [OrderReadController::class, ReadSecurityConfiguration::class, F3TestInputs.SecurityInputs::class],
)
class StoreOrderHistoryHttpTest {
    @Autowired
    lateinit var mvc: MockMvc

    @MockitoBean
    lateinit var orders: DirectOrders

    @ParameterizedTest
    @ValueSource(strings = ["ROLE_USER", "ROLE_ADMIN"])
    fun given__direct_only_and_verified_member__when__listing_then_reading_detail__then__use_principal_and_preserve_snapshot_wire(
        role: String,
    ) {
        whenever(orders.list(7L, null, defaultRequest())).thenReturn(page())
        whenever(orders.get(7L, 77L)).thenReturn(snapshot())
        mvc.perform(
            get("/api/v1/orders")
                .header("Authorization", "Bearer " + F3TestInputs.token(7, role))
                .header("X-User-Id", "99")
                .header("X-User-Role", "ROLE_ADMIN")
                .param("userId", "99"),
        )
            .andExpect(status().isOk())
            .andExpect(header().doesNotExist("Set-Cookie"))
            .andExpect(jsonPath("$.data").doesNotExist())
            .andExpect(jsonPath("$.number").value(0))
            .andExpect(jsonPath("$.size").value(10))
            .andExpect(jsonPath("$.totalElements").value(1))
            .andExpect(jsonPath("$.content[0].orderId").value(77))
            .andExpect(jsonPath("$.content[0].userId").value(7))
            .andExpect(jsonPath("$.content[0].receiverName").value("snapshot receiver"))
            .andExpect(jsonPath("$.content[0].items[0].productName").value("purchased item"))
            .andExpect(jsonPath("$.content[0].items[0].price").value(2600))
        mvc.perform(
            get("/api/v1/orders/77")
                .header("Authorization", "Bearer " + F3TestInputs.token(7, role))
                .header("X-User-Id", "99"),
        )
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.orderId").value(77))
            .andExpect(jsonPath("$.items[0].price").value(2600))
        verify(orders).list(7L, null, defaultRequest())
        verify(orders).get(7L, 77L)
        verifyNoMoreInteractions(orders)
    }

    @ParameterizedTest
    @ValueSource(strings = ["missing", "invalid"])
    fun given__direct_only_without_valid_jwt__when__forging_owner_on_history__then__reject_before_application(
        kind: String,
    ) {
        val call = get("/api/v1/orders")
            .header("X-User-Id", "99")
            .header("X-User-Role", "ROLE_ADMIN")
            .param("userId", "99")
        if (kind == "invalid") {
            call.header("Authorization", "Bearer invalid-token")
        }
        mvc.perform(call).andExpect(status().isUnauthorized())
        verifyNoInteractions(orders)
    }

    @ParameterizedTest
    @CsvSource("GET,/api/orders", "GET,/api/v1/orders/77/extra", "GET,/api/v1/orders/77/payment", "PATCH,/api/v1/orders")
    fun given__direct_only_and_verified_member__when__using_history_neighbors__then__deny_before_application(
        method: String,
        path: String,
    ) {
        mvc.perform(
            request(HttpMethod.valueOf(method), path)
                .header("Authorization", "Bearer " + F3TestInputs.token(7, "ROLE_USER")),
        )
            .andExpect(status().isForbidden())
        verifyNoInteractions(orders)
    }

    companion object {
        fun defaultRequest(): StoreMemberReads.Request = StoreMemberReads.Request(0, 10, listOf())

        fun page(): StoreMemberReads.Page<DirectOrders.OrderDetails> {
            return StoreMemberReads.Page(listOf(snapshot()), 0, 10, 1, listOf())
        }

        fun snapshot(): DirectOrders.OrderDetails {
            return DirectOrders.OrderDetails(
                77L, "snapshot-order", 7L, 5200L, "PENDING", "READY",
                "snapshot receiver", "snapshot phone", "snapshot address", "snapshot message",
                LocalDateTime.of(2026, 10, 21, 0, 0),
                listOf(DirectOrders.Item("purchased item", "SAVED-80", 2600L, 2)),
            )
        }
    }
}

@WebMvcTest(
    properties = ["pawbridge.store-carts.enabled=true"],
    excludeAutoConfiguration = [UserDetailsServiceAutoConfiguration::class],
)
@ContextConfiguration(
    classes = [OrderReadController::class, ReadSecurityConfiguration::class, F3TestInputs.SecurityInputs::class],
)
class StoreOrderHistoryCartOnlyHttpTest {
    @Autowired
    lateinit var mvc: MockMvc

    @MockitoBean
    lateinit var orders: DirectOrders

    @Test
    fun given__cart_only_and_verified_member__when__listing_history__then__allow_the_shared_read_with_verified_identity() {
        whenever(orders.list(7L, "PENDING", StoreOrderHistoryHttpTest.defaultRequest()))
            .thenReturn(StoreOrderHistoryHttpTest.page())
        mvc.perform(
            get("/api/v1/orders")
                .param("status", "PENDING")
                .param("userId", "99")
                .header("Authorization", "Bearer " + F3TestInputs.token(7, "ROLE_USER"))
                .header("X-User-Id", "99"),
        )
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.content[0].status").value("PENDING"))
        verify(orders).list(7L, "PENDING", StoreOrderHistoryHttpTest.defaultRequest())
        verifyNoMoreInteractions(orders)
    }
}

@WebMvcTest(
    properties = ["pawbridge.store-direct-orders.enabled=TRUE", "pawbridge.store-carts.enabled=TrUe"],
    excludeAutoConfiguration = [UserDetailsServiceAutoConfiguration::class],
)
@ContextConfiguration(
    classes = [OrderReadController::class, ReadSecurityConfiguration::class, F3TestInputs.SecurityInputs::class],
)
class StoreOrderHistoryBothEnabledHttpTest {
    @Autowired
    lateinit var mvc: MockMvc

    @MockitoBean
    lateinit var orders: DirectOrders

    @Test
    fun given__both_owner_flags_explicitly_true__when__listing_history__then__invoke_the_same_read_once() {
        whenever(orders.list(7L, null, StoreOrderHistoryHttpTest.defaultRequest()))
            .thenReturn(StoreOrderHistoryHttpTest.page())
        mvc.perform(get("/api/v1/orders").header("Authorization", "Bearer " + F3TestInputs.token(7, "ROLE_USER")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.content[0].orderId").value(77))
        verify(orders).list(7L, null, StoreOrderHistoryHttpTest.defaultRequest())
        verifyNoMoreInteractions(orders)
    }
}
