package com.pawbridge.backend.store.internal.directorder.web

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.pawbridge.backend.store.internal.directorder.application.CartOrders
import com.pawbridge.backend.store.internal.directorder.application.DirectOrders
import com.pawbridge.backend.store.internal.directorder.application.OrderActorRejectedException
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import org.junit.jupiter.params.provider.ValueSource
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.verifyNoMoreInteractions
import org.postgresql.util.PSQLException
import org.postgresql.util.ServerErrorMessage
import org.springframework.dao.DataAccessResourceFailureException
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.http.MediaType
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.GrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.transaction.TransactionSystemException
import java.time.LocalDateTime
import java.util.stream.Stream

class CartOrderControllerTest {
    val orders: CartOrders = mock(CartOrders::class.java)
    val json: ObjectMapper = ObjectMapper().registerModule(JavaTimeModule())
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
        .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
    val command = CartOrders.Command("receiver", "phone", "address", "message")
    val request =
        "{\"receiverName\":\"receiver\",\"receiverPhone\":\"phone\",\"deliveryAddress\":\"address\"," +
            "\"deliveryMessage\":\"message\",\"userId\":99,\"skuId\":999,\"quantity\":999}"
    val response = DirectOrders.OrderDetails(
        9007199254740993L, "fixture-uuid", 7L, 9007199254740993L, "PENDING", "READY", "receiver", "phone", "address", "message",
        LocalDateTime.of(2026, 10, 21, 2, 0, 1, 123456789),
        listOf(DirectOrders.Item("product", "SKU-80", 9007199254740993L, 1)),
    )
    lateinit var mvc: MockMvc

    @BeforeEach
    fun setup() {
        SecurityContextHolder.clearContext()
        mvc = MockMvcBuilders.standaloneSetup(CartOrderController(orders))
            .setControllerAdvice(DirectOrderExceptionHandler())
            .setCustomArgumentResolvers(AuthenticationPrincipalArgumentResolver())
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
    fun given__verified_member_and_shipping_fields__when__checking_out__then__raw_original_201_order_details_and_no_client_cart_or_user_override() {
        current(7L)
        `when`(orders.create(7, command)).thenReturn(response)

        val body = mvc.perform(
            post("/api/v1/orders").header("X-User-Id", "99").param("userId", "99")
                .contentType(MediaType.APPLICATION_JSON).content(request),
        ).andExpect(status().isCreated()).andReturn().response.contentAsByteArray

        val node = json.readTree(body)
        assertEquals(12, node.size())
        assertEquals(4, node.get("items").get(0).size())
        assertEquals(json.readTree(json.writeValueAsBytes(response)), node)
        assertEquals(9007199254740993L, node.get("orderId").longValue())
        assertEquals("2026-10-21T02:00:01.123456789", node.get("createdAt").asText())
        verify(orders).create(7, command)
        verifyNoMoreInteractions(orders)
    }

    @Test
    fun given__original_unvalidated_shipping__when__mapping_request__then__forward_nulls_without_new_notblank_or_size_rules() {
        current(7L)
        val empty = CartOrders.Command(null, null, null, null)
        `when`(orders.create(7, empty)).thenReturn(response)

        mvc.perform(post("/api/v1/orders").contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isCreated())

        verify(orders).create(7, empty)
        verifyNoMoreInteractions(orders)
    }

    @ParameterizedTest
    @ValueSource(strings = ["missing", "text"])
    fun given__missing_or_non_long_principal__when__checking_out__then__unauthorized_before_application(kind: String) {
        if (kind == "text") {
            current("99")
        }

        mvc.perform(
            post("/api/v1/orders").header("X-User-Id", "99").contentType(MediaType.APPLICATION_JSON).content(request),
        ).andExpect(status().isUnauthorized()).andExpect(content().string(""))

        verifyNoInteractions(orders)
    }

    @ParameterizedTest
    @MethodSource("failures")
    fun given__actor_validation_or_persistence_failure__when__checking_out__then__existing_scoped_error_contract_without_private_details(
        failure: RuntimeException,
        statusCode: Int,
        code: String,
    ) {
        current(7L)
        `when`(orders.create(7, command)).thenThrow(failure)

        val body = mvc.perform(post("/api/v1/orders").contentType(MediaType.APPLICATION_JSON).content(request))
            .andExpect(status().`is`(statusCode))
            .andExpect(jsonPath("\$.code").value(code))
            .andExpect(jsonPath("\$.errors").isEmpty()).andReturn().response.contentAsString

        assertFalse(body.contains("private"))
    }

    @Test
    fun given__original_orders_charset_constraint__when__flushing_checkout__then__existing_c007_without_row_or_sql_details() {
        current(7L)
        val sql = PSQLException(
            ServerErrorMessage(
                "SERROR\u0000C23514\u0000Mprivate_fixture\u0000Dprivate_row\u0000spawbridge_store\u0000nck_rollback_charset_orders\u0000",
            ),
        )
        `when`(orders.create(7, command)).thenThrow(DataIntegrityViolationException("private SQL", sql))

        val body = mvc.perform(post("/api/v1/orders").contentType(MediaType.APPLICATION_JSON).content(request))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("\$.code").value("C007")).andReturn().response.contentAsString

        assertFalse(body.contains("private"))
        assertFalse(body.contains("ck_rollback_charset_"))
    }

    companion object {
        @JvmStatic
        fun failures(): Stream<Arguments> = Stream.of(
            Arguments.of(OrderActorRejectedException(), 403, "C006"),
            Arguments.of(IllegalArgumentException("private empty or overflow"), 400, "C001"),
            Arguments.of(DataAccessResourceFailureException("private SQL"), 500, "C004"),
            Arguments.of(TransactionSystemException("private commit"), 500, "C004"),
        )
    }
}
