package com.pawbridge.backend

import com.ninjasquad.springmockk.MockkBean
import com.pawbridge.backend.boot.configuration.ReadSecurityConfiguration
import com.pawbridge.backend.store.internal.orderadministration.application.AdminOrders
import com.pawbridge.backend.store.internal.orderadministration.web.AdminOrderController
import io.mockk.every
import io.mockk.verify
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.http.MediaType
import org.springframework.test.context.ContextConfiguration
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

@WebMvcTest(
    properties = ["pawbridge.store-order-administration.enabled=true"],
    excludeAutoConfiguration = [UserDetailsServiceAutoConfiguration::class],
)
@ContextConfiguration(
    classes = [AdminOrderController::class, ReadSecurityConfiguration::class, F3TestInputs.SecurityInputs::class],
)
class StoreAdminOrderStatusHttpTest {
    @Autowired
    lateinit var mvc: MockMvc

    @MockkBean
    lateinit var orders: AdminOrders

    @Test
    fun `관리 기능이 켜져 있고 서명된 관리자일 때__상태를 변경하면__검증된 주체로 비즈니스에 도달한다`() {
        // given
        val request = patch("/api/v1/admin/orders/7/status")
            .header("Authorization", "Bearer " + F3TestInputs.token(7, "ROLE_ADMIN"))
            .header("X-User-Id", "99")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"status\":\"CANCELLED\"}")

        every { orders.updateStatus(any(), any(), any()) } returns StoreOrderHistoryHttpTest.snapshot()

        // when
        val result = mvc.perform(request)

        // then
        result.andExpect(status().isOk())
        verify(exactly = 1) { orders.updateStatus(7, 7, "CANCELLED") }
    }

}
