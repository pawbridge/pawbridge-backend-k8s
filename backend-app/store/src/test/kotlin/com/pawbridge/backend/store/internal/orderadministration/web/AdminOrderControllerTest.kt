package com.pawbridge.backend.store.internal.orderadministration.web

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.pawbridge.backend.store.anyNonNull
import com.pawbridge.backend.store.internal.directorder.application.DirectOrders
import com.pawbridge.backend.store.internal.orderadministration.application.AdminOrderNotFoundException
import com.pawbridge.backend.store.internal.orderadministration.application.AdminOrders
import com.pawbridge.backend.store.internal.orderadministration.application.AdministratorRejectedException
import jakarta.persistence.PersistenceException
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import org.junit.jupiter.params.provider.ValueSource
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.verifyNoMoreInteractions
import org.springframework.data.web.PageableHandlerMethodArgumentResolver
import org.springframework.http.MediaType
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.GrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.transaction.TransactionSystemException
import java.time.LocalDateTime
import java.util.stream.Stream

class AdminOrderControllerTest {
    val orders: AdminOrders = mock(AdminOrders::class.java)
    val json: ObjectMapper = ObjectMapper().registerModule(JavaTimeModule())
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
        .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
    val detail = DirectOrders.OrderDetails(
        9007199254740993L, "stored-uuid", 88L, 9007199254740993L, "PENDING", "READY", "receiver", "phone", "address", "message",
        LocalDateTime.of(2026, 10, 21, 2, 30, 1, 123456789),
        listOf(DirectOrders.Item("stored product", "SKU-80", 9007199254740993L, 1)),
    )
    lateinit var mvc: MockMvc

    @BeforeEach
    fun setup() {
        SecurityContextHolder.clearContext()
        mvc = MockMvcBuilders.standaloneSetup(AdminOrderController(orders))
            .setControllerAdvice(AdminOrderExceptionHandler())
            .setCustomArgumentResolvers(AuthenticationPrincipalArgumentResolver(), PageableHandlerMethodArgumentResolver())
            .setMessageConverters(MappingJackson2HttpMessageConverter(json)).build()
    }

    @AfterEach
    fun clear() {
        SecurityContextHolder.clearContext()
    }

    fun current(user: Any) {
        SecurityContextHolder.getContext().authentication =
            UsernamePasswordAuthenticationToken(user, "unused", listOf<GrantedAuthority>())
    }

    @Test
    fun given__original_filters_and_sort_page__when__listing__then__original_page_wire_and_verified_caller_with_foreign_user_filter() {
        current(7L)
        val query = AdminOrders.Query("PAID", "SHIPPING", 88L, " A%_ B ", "totalAmount", "ASC", 2, 3)
        `when`(orders.list(7, query)).thenReturn(AdminOrders.Page(listOf(detail), 2, 3, 11, "totalAmount", true))

        val bytes = mvc.perform(
            get("/api/v1/admin/orders").header("X-User-Id", "99").header("X-User-Role", "ROLE_ADMIN")
                .param("status", "PAID").param("deliveryStatus", "SHIPPING").param("userId", "88")
                .param("keyword", " A%_ B ")
                .param("sortBy", "totalAmount").param("sortOrder", "ASC").param("page", "2").param("size", "3"),
        ).andExpect(status().isOk()).andReturn().response.contentAsByteArray

        val page = json.readTree(bytes)
        assertFalse(page.has("data"))
        assertEquals(2, page.get("number").intValue())
        assertEquals(3, page.get("size").intValue())
        assertEquals(11L, page.get("totalElements").longValue())
        assertEquals(4, page.get("totalPages").intValue())
        assertFalse(page.get("first").booleanValue())
        assertFalse(page.get("last").booleanValue())
        assertEquals(1, page.get("numberOfElements").intValue())
        assertEquals(12, page.get("content").get(0).size())
        assertEquals(json.readTree(json.writeValueAsBytes(detail)), page.get("content").get(0))
        assertTrue(page.get("sort").get("sorted").booleanValue())
        verify(orders).list(7, query)
        verifyNoMoreInteractions(orders)
    }

    @Test
    fun given__default_query__when__listing__then__created_at_desc_default_twenty_and_empty_page() {
        current(7L)
        val query = AdminOrders.Query(null, null, null, null, "createdAt", "desc", 0, 20)
        `when`(orders.list(7, query)).thenReturn(AdminOrders.Page(listOf(), 0, 20, 0, "createdAt", false))

        mvc.perform(request("list")).andExpect(status().isOk())
            .andExpect(jsonPath("\$.content").isEmpty())
            .andExpect(jsonPath("\$.number").value(0))
            .andExpect(jsonPath("\$.size").value(20))

        verify(orders).list(7, query)
    }

    @Test
    fun given__verified_caller__when__reading_detail__then__raw_original_order_details() {
        current(7L)
        `when`(orders.get(7, 33)).thenReturn(detail)

        val bytes = mvc.perform(request("get").header("X-User-Id", "99")).andExpect(status().isOk())
            .andReturn().response.contentAsByteArray

        assertEquals(json.readTree(json.writeValueAsBytes(detail)), json.readTree(bytes))
        verify(orders).get(7, 33)
        verifyNoMoreInteractions(orders)
    }

    @ParameterizedTest
    @ValueSource(strings = ["READY", "SHIPPING", "DELIVERED"])
    fun given__delivery_enum_and_forged_other_fields__when__patching__then__only_delivery_and_verified_caller_are_forwarded(
        delivery: String,
    ) {
        current(7L)
        `when`(orders.updateDelivery(7, 33, delivery)).thenReturn(detail)

        mvc.perform(
            patch("/api/v1/admin/orders/33/delivery-status").header("X-User-Id", "99")
                .header("X-User-Role", "ROLE_ADMIN").contentType(MediaType.APPLICATION_JSON)
                .content("{\"deliveryStatus\":\"" + delivery + "\",\"status\":\"PAID\",\"userId\":99}"),
        ).andExpect(status().isOk()).andExpect(jsonPath("\$.status").value("PENDING"))

        verify(orders).updateDelivery(7, 33, delivery)
        verifyNoMoreInteractions(orders)
    }

    @ParameterizedTest
    @MethodSource("principals")
    fun given__missing_or_non_long_principal_with_forged_admin_header__when__using_any_endpoint__then__unauthorized_without_application(
        kind: String,
        op: String,
    ) {
        if (kind == "text") {
            current("99")
        }

        mvc.perform(request(op).header("X-User-Id", "99").header("X-User-Role", "ROLE_ADMIN"))
            .andExpect(status().isUnauthorized()).andExpect(content().string(""))

        verifyNoInteractions(orders)
    }

    @ParameterizedTest
    @ValueSource(strings = ["list", "get", "write"])
    fun given__current_database_admin_rejected__when__using_any_endpoint__then__forbidden_without_private_details(op: String) {
        current(7L)
        val failure = AdministratorRejectedException()
        if (op == "list") {
            `when`(orders.list(eq(7L), anyNonNull<AdminOrders.Query>())).thenThrow(failure)
        } else if (op == "get") {
            `when`(orders.get(7, 33)).thenThrow(failure)
        } else {
            `when`(orders.updateDelivery(7, 33, "SHIPPING")).thenThrow(failure)
        }

        mvc.perform(request(op)).andExpect(status().isForbidden()).andExpect(jsonPath("\$.code").value("C006"))
    }

    @Test
    fun given__missing_order__when__reading_and_patching__then__original_404_and_400_distinction() {
        current(7L)
        `when`(orders.get(7, 33)).thenThrow(AdminOrderNotFoundException())
        `when`(orders.updateDelivery(7, 33, "SHIPPING")).thenThrow(IllegalArgumentException("private missing id"))

        mvc.perform(request("get")).andExpect(status().isNotFound())
            .andExpect(jsonPath("\$.code").value("O001"))
            .andExpect(jsonPath("\$.message").value("Order not found"))
        mvc.perform(request("write")).andExpect(status().isBadRequest()).andExpect(jsonPath("\$.code").value("C001"))
    }

    @ParameterizedTest
    @ValueSource(strings = ["status", "deliveryStatus", "userId"])
    fun given__wrong_query_type__when__listing__then__original_field_type_error_before_application(field: String) {
        current(7L)

        mvc.perform(get("/api/v1/admin/orders").param(field, "bad")).andExpect(status().isBadRequest())
            .andExpect(jsonPath("\$.code").value("C005"))
            .andExpect(jsonPath("\$.errors[0].field").value(field))
            .andExpect(jsonPath("\$.errors[0].reason").value("typeMismatch"))

        verifyNoInteractions(orders)
    }

    @ParameterizedTest
    @ValueSource(strings = ["get", "write"])
    fun given__wrong_order_id_type__when__accessing__then__original_bad_type_contract(op: String) {
        current(7L)
        val call = if (op == "get") {
            get("/api/v1/admin/orders/bad")
        } else {
            patch("/api/v1/admin/orders/bad/delivery-status").contentType(MediaType.APPLICATION_JSON)
                .content("{\"deliveryStatus\":\"READY\"}")
        }

        mvc.perform(call).andExpect(status().isBadRequest())
            .andExpect(jsonPath("\$.code").value("C005"))
            .andExpect(jsonPath("\$.errors[0].field").value("orderId"))

        verifyNoInteractions(orders)
    }

    @ParameterizedTest
    @ValueSource(strings = ["{}", "{\"deliveryStatus\":null}"])
    fun given__null_delivery_value__when__patching__then__preserve_original_500_generic_failure_family(body: String) {
        current(7L)
        `when`(orders.updateDelivery(7, 33, null)).thenThrow(IllegalStateException("Delivery status cannot be stored"))

        val response = mvc.perform(
            patch("/api/v1/admin/orders/33/delivery-status").contentType(MediaType.APPLICATION_JSON).content(body),
        ).andExpect(status().isInternalServerError())
            .andExpect(jsonPath("\$.code").value("C004")).andReturn().response.contentAsString

        assertFalse(response.contains("private"))
        verify(orders).updateDelivery(7, 33, null)
    }

    @ParameterizedTest
    @ValueSource(strings = ["{\"deliveryStatus\":\"UNKNOWN\"}", "{"])
    fun given__invalid_enum_json_or_malformed_json__when__patching__then__original_generic_server_error_binding_family(
        body: String,
    ) {
        current(7L)

        mvc.perform(
            patch("/api/v1/admin/orders/33/delivery-status").contentType(MediaType.APPLICATION_JSON).content(body),
        ).andExpect(status().isInternalServerError()).andExpect(jsonPath("\$.code").value("C004"))

        verifyNoInteractions(orders)
    }

    @ParameterizedTest
    @ValueSource(strings = ["query", "commit"])
    fun given__database_or_commit_failure__when__patching__then__server_error_without_sql_or_order_details(kind: String) {
        current(7L)
        val failure: RuntimeException = if (kind == "query") {
            PersistenceException("private query")
        } else {
            TransactionSystemException("private commit")
        }
        `when`(orders.updateDelivery(7, 33, "SHIPPING")).thenThrow(failure)

        val body = mvc.perform(request("write")).andExpect(status().isInternalServerError())
            .andExpect(jsonPath("\$.code").value("C004"))
            .andReturn().response.contentAsString

        assertFalse(body.contains("private"))
        assertFalse(body.contains("address"))
    }

    @Test
    fun given__unported_payment_path__when__requesting__then__no_handler() {
        current(7L)

        mvc.perform(post("/api/v1/orders/33/payment")).andExpect(status().isNotFound())

        verifyNoInteractions(orders)
    }

    @ParameterizedTest
    @ValueSource(strings = ["PENDING", "PAID", "COMPLETED", "CANCELLED", "FAILED"])
    fun given__status_enum_and_forged_other_fields__when__patching_status__then__only_status_and_verified_caller_are_forwarded(
        status: String,
    ) {
        current(7L)
        `when`(orders.updateStatus(7, 33, status)).thenReturn(detail)

        val bytes = mvc.perform(
            patch("/api/v1/admin/orders/33/status").header("X-User-Id", "99").header("X-User-Role", "ROLE_ADMIN")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"" + status + "\",\"deliveryStatus\":\"DELIVERED\",\"userId\":99}"),
        ).andExpect(status().isOk()).andReturn().response.contentAsByteArray

        assertEquals(json.readTree(json.writeValueAsBytes(detail)), json.readTree(bytes))
        verify(orders).updateStatus(7, 33, status)
        verifyNoMoreInteractions(orders)
    }

    @ParameterizedTest
    @ValueSource(strings = ["missing", "text"])
    fun given__missing_or_non_long_principal__when__patching_status__then__unauthorized_without_application(kind: String) {
        if (kind == "text") {
            current("99")
        }

        mvc.perform(
            patch("/api/v1/admin/orders/33/status").header("X-User-Role", "ROLE_ADMIN")
                .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"PAID\"}"),
        ).andExpect(status().isUnauthorized()).andExpect(content().string(""))

        verifyNoInteractions(orders)
    }

    @Test
    fun given__rejected_admin_or_missing_order__when__patching_status__then__original_403_and_400() {
        current(7L)
        `when`(orders.updateStatus(7, 33, "PAID")).thenThrow(AdministratorRejectedException())
        `when`(orders.updateStatus(7, 34, "PAID")).thenThrow(IllegalArgumentException("private missing id"))

        mvc.perform(
            patch("/api/v1/admin/orders/33/status").contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"PAID\"}"),
        ).andExpect(status().isForbidden()).andExpect(jsonPath("\$.code").value("C006"))
        mvc.perform(
            patch("/api/v1/admin/orders/34/status").contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"PAID\"}"),
        ).andExpect(status().isBadRequest()).andExpect(jsonPath("\$.code").value("C001"))
    }

    @ParameterizedTest
    @ValueSource(strings = ["{}", "{\"status\":null}"])
    fun given__null_status_value__when__patching__then__preserve_original_500_generic_failure_family(body: String) {
        current(7L)
        `when`(orders.updateStatus(7, 33, null)).thenThrow(IllegalStateException("Order status cannot be stored"))

        mvc.perform(patch("/api/v1/admin/orders/33/status").contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isInternalServerError())
            .andExpect(jsonPath("\$.code").value("C004"))

        verify(orders).updateStatus(7, 33, null)
    }

    @ParameterizedTest
    @ValueSource(strings = ["{\"status\":\"UNKNOWN\"}", "{"])
    fun given__invalid_status_enum_or_malformed_json__when__patching__then__original_generic_server_error_binding_family(
        body: String,
    ) {
        current(7L)

        mvc.perform(patch("/api/v1/admin/orders/33/status").contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isInternalServerError())
            .andExpect(jsonPath("\$.code").value("C004"))

        verifyNoInteractions(orders)
    }

    @Test
    fun given__wrong_order_id_type__when__patching_status__then__original_bad_type_contract() {
        current(7L)

        mvc.perform(
            patch("/api/v1/admin/orders/bad/status").contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"PAID\"}"),
        ).andExpect(status().isBadRequest())
            .andExpect(jsonPath("\$.code").value("C005"))
            .andExpect(jsonPath("\$.errors[0].field").value("orderId"))

        verifyNoInteractions(orders)
    }

    companion object {
        fun request(op: String): MockHttpServletRequestBuilder {
            return when (op) {
                "list" -> get("/api/v1/admin/orders")
                "get" -> get("/api/v1/admin/orders/33")
                else -> patch("/api/v1/admin/orders/33/delivery-status").contentType(MediaType.APPLICATION_JSON)
                    .content("{\"deliveryStatus\":\"SHIPPING\",\"role\":\"ROLE_ADMIN\",\"userId\":99,\"status\":\"PAID\"}")
            }
        }

        @JvmStatic
        fun principals(): Stream<Arguments> {
            return Stream.of("missing", "text")
                .flatMap { kind -> Stream.of("list", "get", "write").map { op -> Arguments.of(kind, op) } }
        }
    }
}
