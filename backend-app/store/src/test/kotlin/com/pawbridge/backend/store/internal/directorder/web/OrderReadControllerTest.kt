package com.pawbridge.backend.store.internal.directorder.web

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.pawbridge.backend.store.anyNonNull
import com.pawbridge.backend.store.api.StoreMemberReads
import com.pawbridge.backend.store.internal.directorder.application.DirectOrders
import com.pawbridge.backend.store.internal.directorder.application.OrderActorRejectedException
import com.pawbridge.backend.store.internal.memberread.infrastructure.jpa.model.OrderStatus
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.EnumSource
import org.junit.jupiter.params.provider.MethodSource
import org.junit.jupiter.params.provider.NullSource
import org.junit.jupiter.params.provider.ValueSource
import org.mockito.ArgumentMatchers.eq
import org.mockito.ArgumentMatchers.isNull
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.verifyNoMoreInteractions
import org.springframework.dao.DataAccessResourceFailureException
import org.springframework.data.web.PageableHandlerMethodArgumentResolver
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.GrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import java.time.LocalDateTime
import java.util.stream.Stream

class OrderReadControllerTest {
    private val orders: DirectOrders = mock(DirectOrders::class.java)
    private val json: ObjectMapper = ObjectMapper().registerModule(JavaTimeModule())
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
    private val order = DirectOrders.OrderDetails(
        9007199254740993L, "purchased-uuid", 7L,
        9007199254740993L, "PENDING", "READY", "saved receiver", "saved phone", "saved address", "saved message",
        LocalDateTime.of(2026, 10, 20, 22, 0, 1, 123456789),
        listOf(DirectOrders.Item("saved product", "SAVED-80", 9007199254740993L, 2)),
    )
    private lateinit var mvc: MockMvc

    @BeforeEach
    fun setup() {
        SecurityContextHolder.clearContext()
        mvc = MockMvcBuilders.standaloneSetup(OrderReadController(orders))
            .setControllerAdvice(DirectOrderExceptionHandler())
            .setCustomArgumentResolvers(AuthenticationPrincipalArgumentResolver(), PageableHandlerMethodArgumentResolver())
            .setMessageConverters(MappingJackson2HttpMessageConverter(json)).build()
    }

    @AfterEach
    fun clear() {
        SecurityContextHolder.clearContext()
    }

    private fun current(member: Any) {
        SecurityContextHolder.getContext().authentication =
            UsernamePasswordAuthenticationToken(member, "unused", listOf<GrantedAuthority>())
    }

    @ParameterizedTest
    @NullSource
    @EnumSource(OrderStatus::class)
    fun given__verified_member_and_optional_status_with_forged_owner__when__listing__then__forward_verified_owner_and_default_ten_and_return_direct_page(
        statusFilter: OrderStatus?,
    ) {
        current(7L)
        val request = StoreMemberReads.Request(0, 10, listOf())
        `when`(orders.list(7, statusFilter?.name, request))
            .thenReturn(StoreMemberReads.Page(listOf(order), 0, 10, 1, listOf()))
        val call = get("/api/v1/orders").param("userId", "99").header("X-User-Id", "99").header("userId", "99")
        if (statusFilter != null) {
            call.param("status", statusFilter.name)
        }

        val bytes = mvc.perform(call).andExpect(status().isOk()).andReturn().response.contentAsByteArray

        val body = json.readTree(bytes)
        assertFalse(body.has("data"))
        assertEquals(1, body.get("content").size())
        assertEquals(0, body.get("number").intValue())
        assertEquals(10, body.get("size").intValue())
        assertEquals(1L, body.get("totalElements").longValue())
        assertEquals(1, body.get("totalPages").intValue())
        assertTrue(body.get("sort").get("unsorted").booleanValue())
        val item = body.get("content").get(0)
        assertEquals(json.readTree(json.writeValueAsBytes(order)), item)
        assertEquals(12, item.size())
        assertEquals(4, item.get("items").get(0).size())
        assertEquals(9007199254740993L, item.get("orderId").longValue())
        assertEquals(9007199254740993L, item.get("items").get(0).get("price").longValue())
        assertEquals("2026-10-20T22:00:01.123456789", item.get("createdAt").asText())
        verify(orders).list(7, statusFilter?.name, request)
        verifyNoMoreInteractions(orders)
    }

    @Test
    fun given__explicit_page_status_and_scalar_sorts__when__listing_then_reading_existing_detail__then__preserve_page_metadata_sort_and_identical_order_wire() {
        current(7L)
        val sort = listOf(
            StoreMemberReads.Order("totalAmount", true, false),
            StoreMemberReads.Order("receiverName", false, true),
        )
        val request = StoreMemberReads.Request(3, 2, sort)
        `when`(orders.list(7, "PAID", request)).thenReturn(StoreMemberReads.Page(listOf(order), 3, 2, 7, sort))
        `when`(orders.get(7, 9007199254740993L)).thenReturn(order)

        val history = json.readTree(
            mvc.perform(
                get("/api/v1/orders").param("status", "PAID").param("page", "3").param("size", "2")
                    .param("sort", "totalAmount,asc", "receiverName,desc,ignorecase"),
            )
                .andExpect(status().isOk()).andExpect(jsonPath("\$.number").value(3)).andExpect(jsonPath("\$.size").value(2))
                .andExpect(jsonPath("\$.totalElements").value(7)).andExpect(jsonPath("\$.totalPages").value(4))
                .andExpect(jsonPath("\$.sort.sorted").value(true)).andReturn().response.contentAsByteArray,
        )
        val detail = json.readTree(
            mvc.perform(get("/api/v1/orders/9007199254740993"))
                .andExpect(status().isOk()).andReturn().response.contentAsByteArray,
        )

        assertEquals(detail, history.get("content").get(0))
        verify(orders).list(7, "PAID", request)
        verify(orders).get(7, 9007199254740993L)
        verifyNoMoreInteractions(orders)
    }

    @ParameterizedTest
    @ValueSource(strings = ["missing", "non-long"])
    fun given__missing_or_non_long_principal_with_forged_owner__when__listing__then__unauthorized_before_application(
        kind: String,
    ) {
        if (kind == "non-long") {
            current("99")
        }

        mvc.perform(get("/api/v1/orders").param("userId", "99").header("X-User-Id", "99").header("userId", "99"))
            .andExpect(status().isUnauthorized()).andExpect(content().string(""))

        verifyNoInteractions(orders)
    }

    @Test
    fun given__unsupported_status__when__listing__then__original_type_error_without_application() {
        current(7L)

        mvc.perform(get("/api/v1/orders").param("status", "UNKNOWN"))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("\$.code").value("C005"))
            .andExpect(jsonPath("\$.errors[0].field").value("status"))
            .andExpect(jsonPath("\$.errors[0].value").value("UNKNOWN"))
            .andExpect(jsonPath("\$.errors[0].reason").value("typeMismatch"))

        verifyNoInteractions(orders)
    }

    @ParameterizedTest
    @MethodSource("failures")
    fun given__current_actor_or_query_or_database_rejection__when__listing__then__preserve_sanitized_error_without_order_data(
        failure: RuntimeException,
        statusCode: Int,
        code: String,
        message: String,
    ) {
        current(7L)
        `when`(orders.list(eq(7L), isNull(), anyNonNull<StoreMemberReads.Request>())).thenThrow(failure)

        val body = mvc.perform(get("/api/v1/orders")).andExpect(status().`is`(statusCode))
            .andExpect(jsonPath("\$.code").value(code))
            .andExpect(jsonPath("\$.message").value(message))
            .andExpect(jsonPath("\$.errors").isEmpty())
            .andReturn().response.contentAsString

        assertFalse(body.contains("private"))
        assertFalse(body.contains("purchased"))
        assertFalse(body.contains("saved"))
        verify(orders).list(7, null, StoreMemberReads.Request(0, 10, listOf()))
        verifyNoMoreInteractions(orders)
    }

    @ParameterizedTest
    @ValueSource(
        strings = ["/api/orders", "/api/v1/orders/uuid/purchased-uuid", "/api/v1/orders/77/payment", "/api/v1/orders/cart/direct"],
    )
    fun given__neighboring_unapproved_route__when__requesting__then__no_order_history_handler(path: String) {
        current(7L)

        mvc.perform(get(path)).andExpect(status().isNotFound())

        verifyNoInteractions(orders)
    }

    companion object {
        @JvmStatic
        fun failures(): Stream<Arguments> = Stream.of(
            Arguments.of(OrderActorRejectedException(), 403, "C006", "Access is Denied"),
            Arguments.of(IllegalArgumentException("private query and account"), 400, "C001", "Invalid Input Value"),
            Arguments.of(DataAccessResourceFailureException("private query and account"), 500, "C004", "Server Error"),
        )
    }
}
