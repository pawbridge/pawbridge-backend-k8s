package com.pawbridge.backend

import com.ninjasquad.springmockk.MockkBean
import com.pawbridge.backend.boot.configuration.ReadSecurityConfiguration
import com.pawbridge.backend.store.api.StoreMemberReads
import com.pawbridge.backend.store.internal.directorder.application.DirectOrders
import com.pawbridge.backend.store.internal.directorder.web.OrderReadController
import io.mockk.confirmVerified
import io.mockk.every
import io.mockk.verify
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.test.context.ContextConfiguration
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

    @MockkBean
    lateinit var orders: DirectOrders

    @ParameterizedTest
    @ValueSource(strings = ["ROLE_USER", "ROLE_ADMIN"])
    fun `직접 주문만 켜졌고 검증된 회원일 때__목록과 상세를 읽으면__주체를 쓰고 스냅샷 응답을 보존한다`(
        role: String,
    ) {
        // given
        val listRequest = get("/api/v1/orders")
            .header("Authorization", "Bearer " + F3TestInputs.token(7, role))
            .header("X-User-Id", "99")
            .header("X-User-Role", "ROLE_ADMIN")
            .param("userId", "99")
        val detailRequest = get("/api/v1/orders/77")
            .header("Authorization", "Bearer " + F3TestInputs.token(7, role))
            .header("X-User-Id", "99")

        every { orders.list(any(), any(), any()) } returns page()
        every { orders.get(any(), any()) } returns snapshot()

        // when
        val listResult = mvc.perform(listRequest)
        val detailResult = mvc.perform(detailRequest)

        // then
        listResult
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
        detailResult
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.orderId").value(77))
            .andExpect(jsonPath("$.items[0].price").value(2600))
        verify(exactly = 1) { orders.list(7L, null, defaultRequest()) }
        verify(exactly = 1) { orders.get(7L, 77L) }
        confirmVerified(orders)
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

    @MockkBean
    lateinit var orders: DirectOrders

    @Test
    fun `장바구니만 켜졌고 검증된 회원일 때__내역을 조회하면__검증된 신원으로 공유 읽기를 허용한다`() {
        // given
        val request = get("/api/v1/orders")
            .param("status", "PENDING")
            .param("userId", "99")
            .header("Authorization", "Bearer " + F3TestInputs.token(7, "ROLE_USER"))
            .header("X-User-Id", "99")

        every { orders.list(any(), any(), any()) } returns StoreOrderHistoryHttpTest.page()

        // when
        val result = mvc.perform(request)

        // then
        result
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.content[0].status").value("PENDING"))
        verify(exactly = 1) { orders.list(7L, "PENDING", StoreOrderHistoryHttpTest.defaultRequest()) }
        confirmVerified(orders)
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

    @MockkBean
    lateinit var orders: DirectOrders

    @Test
    fun `두 소유자 플래그가 모두 true일 때__내역을 조회하면__같은 읽기를 한 번 호출한다`() {
        // given
        val request = get("/api/v1/orders").header("Authorization", "Bearer " + F3TestInputs.token(7, "ROLE_USER"))

        every { orders.list(any(), any(), any()) } returns StoreOrderHistoryHttpTest.page()

        // when
        val result = mvc.perform(request)

        // then
        result
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.content[0].orderId").value(77))
        verify(exactly = 1) { orders.list(7L, null, StoreOrderHistoryHttpTest.defaultRequest()) }
        confirmVerified(orders)
    }
}
