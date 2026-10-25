package com.pawbridge.backend

import com.pawbridge.backend.boot.configuration.ReadSecurityConfiguration
import com.pawbridge.backend.store.internal.orderadministration.application.AdminOrders
import com.pawbridge.backend.store.internal.orderadministration.web.AdminOrderController
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.http.MediaType
import org.springframework.test.context.ContextConfiguration
import org.springframework.test.context.bean.override.mockito.MockitoBean
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

    @MockitoBean
    lateinit var orders: AdminOrders

    @Test
    fun given__enabled_administration_and_signed_admin__when__patching_status__then__reach_business_with_verified_principal() {
        mvc.perform(
            patch("/api/v1/admin/orders/7/status")
                .header("Authorization", "Bearer " + F3TestInputs.token(7, "ROLE_ADMIN"))
                .header("X-User-Id", "99")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"CANCELLED\"}"),
        ).andExpect(status().isOk())
        verify(orders).updateStatus(7, 7, "CANCELLED")
    }

    @ParameterizedTest
    @ValueSource(strings = ["ROLE_USER", "ROLE_SHELTER"])
    fun given__signed_non_admin_with_forged_admin_header__when__patching_status__then__forbidden_before_business(
        role: String,
    ) {
        mvc.perform(
            patch("/api/v1/admin/orders/7/status")
                .header("Authorization", "Bearer " + F3TestInputs.token(7, role))
                .header("X-User-Role", "ROLE_ADMIN")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"PAID\"}"),
        ).andExpect(status().isForbidden())
        verifyNoInteractions(orders)
    }

    @Test
    fun given__missing_token__when__patching_status__then__unauthorized_before_business() {
        mvc.perform(
            patch("/api/v1/admin/orders/7/status")
                .header("X-User-Role", "ROLE_ADMIN")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"PAID\"}"),
        ).andExpect(status().isUnauthorized())
        verifyNoInteractions(orders)
    }
}
