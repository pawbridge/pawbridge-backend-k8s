package com.pawbridge.backend.store.internal.directorder.web

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.transaction.TransactionSystemException
import java.sql.BatchUpdateException
import java.sql.SQLException
import java.time.LocalDateTime
import java.util.stream.Stream

class DirectOrderControllerTest {
    private val orders: DirectOrders = mock(DirectOrders::class.java)
    private val json: ObjectMapper = ObjectMapper().registerModule(JavaTimeModule())
        .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
    private val command = DirectOrders.Command(80L, 2, "fixture receiver", "fixture phone", "fixture address", "fixture message")
    private val request =
        "{\"skuId\":80,\"quantity\":2,\"receiverName\":\"fixture receiver\",\"receiverPhone\":\"fixture phone\"," +
            "\"deliveryAddress\":\"fixture address\",\"deliveryMessage\":\"fixture message\",\"userId\":99}"
    private val response = DirectOrders.OrderDetails(
        9007199254740993L, "fixture-uuid", 7L, 9007199254740993L, "PENDING", "READY",
        "fixture receiver", "fixture phone", "fixture address", "fixture message",
        LocalDateTime.of(2026, 10, 20, 22, 0, 1, 123456789),
        listOf(DirectOrders.Item("fixture product", "CURRENT-80", 9007199254740993L, 2)),
    )
    private lateinit var mvc: MockMvc

    @BeforeEach
    fun setup() {
        SecurityContextHolder.clearContext()
        mvc = MockMvcBuilders.standaloneSetup(DirectOrderController(orders), OrderReadController(orders))
            .setControllerAdvice(DirectOrderExceptionHandler())
            .setCustomArgumentResolvers(AuthenticationPrincipalArgumentResolver())
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

    private fun fullResponse(bytes: ByteArray) {
        val node = json.readTree(bytes)
        assertEquals(12, node.size())
        assertFalse(node.has("data"))
        assertEquals(json.readTree(json.writeValueAsBytes(response)), node)
        assertEquals(4, node.get("items").get(0).size())
        assertEquals(9007199254740993L, node.get("orderId").longValue())
        assertEquals(9007199254740993L, node.get("items").get(0).get("price").longValue())
        assertEquals("2026-10-20T22:00:01.123456789", node.get("createdAt").asText())
    }

    @Test
    fun given__verified_long_member_and_forged_user_sources__when__buying__then__pass_full_request_with_verified_member_and_return_original_response() {
        current(7L)
        `when`(orders.create(7, command)).thenReturn(response)

        fullResponse(
            mvc.perform(
                post("/api/v1/orders/direct").param("userId", "99").header("X-User-Id", "99")
                    .contentType(MediaType.APPLICATION_JSON).content(request),
            ).andExpect(status().isCreated()).andReturn().response.contentAsByteArray,
        )

        verify(orders).create(7, command)
        verifyNoMoreInteractions(orders)
    }

    @Test
    fun given__verified_long_member_and_forged_user_query__when__reading__then__use_only_verified_member_and_return_full_order() {
        current(7L)
        `when`(orders.get(7, 9007199254740993L)).thenReturn(response)

        fullResponse(
            mvc.perform(get("/api/v1/orders/9007199254740993").param("userId", "99").header("X-User-Id", "99"))
                .andExpect(status().isOk()).andReturn().response.contentAsByteArray,
        )

        verify(orders).get(7, 9007199254740993L)
        verifyNoMoreInteractions(orders)
    }

    @ParameterizedTest
    @MethodSource("invalidPrincipals")
    fun given__missing_or_non_long_principal_with_forged_user__when__accessing__then__unauthorized_before_application(
        kind: String,
        method: String,
    ) {
        if (kind == "text") {
            current("99")
        }
        val call = if (method == "POST") {
            post("/api/v1/orders/direct").contentType(MediaType.APPLICATION_JSON).content(request)
        } else {
            get("/api/v1/orders/33")
        }

        mvc.perform(call.param("userId", "99").header("X-User-Id", "99"))
            .andExpect(status().isUnauthorized())
            .andExpect(content().string(""))

        verifyNoInteractions(orders)
    }

    @ParameterizedTest
    @MethodSource("invalidRequests")
    fun given__invalid_sku_or_quantity__when__buying__then__preserve_original_field_value_reason_errors_without_application(
        body: String,
        field: String,
        value: String,
    ) {
        current(7L)

        val bytes = mvc.perform(post("/api/v1/orders/direct").contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("\$.message").value("Invalid Input Value"))
            .andExpect(jsonPath("\$.code").value("C001"))
            .andReturn().response.contentAsByteArray

        val errors = json.readTree(bytes).get("errors")
        assertEquals(1, errors.size())
        val error = errors.get(0)
        assertEquals(3, error.size())
        assertEquals(field, error.get("field").asText())
        assertEquals(value, error.get("value").asText())
        assertFalse(error.get("reason").asText().isBlank())
        verifyNoInteractions(orders)
    }

    @ParameterizedTest
    @ValueSource(strings = ["bad", "9223372036854775808"])
    fun given__unparseable_order_id__when__reading__then__preserve_original_type_error_without_application(orderId: String) {
        current(7L)

        mvc.perform(get("/api/v1/orders/{orderId}", orderId)).andExpect(status().isBadRequest())
            .andExpect(jsonPath("\$.code").value("C005"))
            .andExpect(jsonPath("\$.errors[0].field").value("orderId"))
            .andExpect(jsonPath("\$.errors[0].value").value(orderId))
            .andExpect(jsonPath("\$.errors[0].reason").value("typeMismatch"))

        verifyNoInteractions(orders)
    }

    @ParameterizedTest
    @MethodSource("failures")
    fun given__actor_or_order_or_transaction_failure__when__buying__then__preserve_status_and_sanitized_body(
        failure: RuntimeException,
        statusCode: Int,
        code: String,
        message: String,
    ) {
        current(7L)
        `when`(orders.create(7, command)).thenThrow(failure)

        val body = mvc.perform(post("/api/v1/orders/direct").contentType(MediaType.APPLICATION_JSON).content(request))
            .andExpect(status().`is`(statusCode))
            .andExpect(jsonPath("\$.code").value(code))
            .andExpect(jsonPath("\$.message").value(message))
            .andExpect(jsonPath("\$.errors").isEmpty())
            .andReturn().response.contentAsString

        assertFalse(body.contains("private"))
        assertFalse(body.contains("fixture"))
    }

    @Test
    fun given__order_not_accessible_to_member__when__reading__then__preserve_source_bad_input_without_order_details() {
        current(7L)
        `when`(orders.get(7, 33)).thenThrow(IllegalArgumentException("private owner"))

        mvc.perform(get("/api/v1/orders/33")).andExpect(status().isBadRequest())
            .andExpect(jsonPath("\$.code").value("C001"))
            .andExpect(jsonPath("\$.errors").isEmpty())
    }

    @ParameterizedTest
    @MethodSource("rollbackCharsetFailures")
    fun given__rollback_charset_constraint_in_write_or_commit_or_batch__when__buying__then__original_c007_without_private_sql_details(
        failure: RuntimeException,
    ) {
        current(7L)
        `when`(orders.create(7, command)).thenThrow(failure)

        val body = mvc.perform(post("/api/v1/orders/direct").contentType(MediaType.APPLICATION_JSON).content(request))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("\$.code").value("C007"))
            .andExpect(jsonPath("\$.message").value("일부 이모지 등 지원하지 않는 문자를 제외해 주세요."))
            .andExpect(jsonPath("\$.errors").isEmpty()).andReturn().response.contentAsString

        assertFalse(body.contains("private"))
        assertFalse(body.contains("ck_rollback_charset_"))
        assertFalse(body.contains("23514"))
    }

    @ParameterizedTest
    @MethodSource("unrelatedConstraintFailures")
    fun given__other_constraint_or_schema_or_sqlstate_or_unstructured_text__when__buying__then__keep_server_error_without_private_sql_details(
        cause: SQLException,
    ) {
        current(7L)
        `when`(orders.create(7, command)).thenThrow(DataIntegrityViolationException("private fixture", cause))

        val body = mvc.perform(post("/api/v1/orders/direct").contentType(MediaType.APPLICATION_JSON).content(request))
            .andExpect(status().isInternalServerError())
            .andExpect(jsonPath("\$.code").value("C004"))
            .andExpect(jsonPath("\$.message").value("Server Error"))
            .andExpect(jsonPath("\$.errors").isEmpty())
            .andReturn().response.contentAsString

        assertFalse(body.contains("private"))
        assertFalse(body.contains("ck_rollback_charset_"))
        assertFalse(body.contains("23514"))
    }

    @ParameterizedTest
    @ValueSource(strings = ["/api/v1/orders/uuid/fixture", "/api/v1/orders/33/payment", "/api/v1/orders/cart/direct"])
    fun given__neighboring_unapproved_flow__when__requesting__then__no_direct_order_handler(path: String) {
        current(7L)

        mvc.perform(get(path)).andExpect(status().isNotFound())

        verifyNoInteractions(orders)
    }

    companion object {
        @JvmStatic
        fun invalidPrincipals(): Stream<Arguments> {
            return Stream.of("missing", "text")
                .flatMap { kind -> Stream.of("POST", "GET").map { method -> Arguments.of(kind, method) } }
        }

        @JvmStatic
        fun invalidRequests(): Stream<Arguments> = Stream.of(
            Arguments.of("{\"skuId\":80,\"quantity\":0}", "quantity", "0"),
            Arguments.of("{\"skuId\":80,\"quantity\":-1}", "quantity", "-1"),
            Arguments.of("{\"skuId\":0,\"quantity\":2}", "skuId", "0"),
            Arguments.of("{\"quantity\":2}", "skuId", ""),
            Arguments.of("{\"skuId\":80}", "quantity", ""),
        )

        @JvmStatic
        fun failures(): Stream<Arguments> = Stream.of(
            Arguments.of(OrderActorRejectedException(), 403, "C006", "Access is Denied"),
            Arguments.of(IllegalArgumentException("private account and shipping"), 400, "C001", "Invalid Input Value"),
            Arguments.of(DataAccessResourceFailureException("private account and shipping"), 500, "C004", "Server Error"),
            Arguments.of(TransactionSystemException("private account and shipping"), 500, "C004", "Server Error"),
        )

        private fun constraint(state: String, schema: String, name: String): PSQLException {
            return PSQLException(
                ServerErrorMessage(
                    "SERROR\u0000C" + state + "\u0000Mprivate_fixture_value\u0000Dprivate_fixture_row\u0000s" +
                        schema + "\u0000n" + name + "\u0000",
                ),
            )
        }

        @JvmStatic
        fun rollbackCharsetFailures(): Stream<Arguments> {
            val violation = constraint("23514", "pawbridge_store", "ck_rollback_charset_orders")
            val batch = BatchUpdateException()
            batch.nextException = violation
            return Stream.of(
                Arguments.of(DataIntegrityViolationException("private fixture", violation)),
                Arguments.of(TransactionSystemException("private commit", violation)),
                Arguments.of(DataIntegrityViolationException("private batch", batch)),
            )
        }

        @JvmStatic
        fun unrelatedConstraintFailures(): Stream<Arguments> = Stream.of(
            Arguments.of(constraint("23514", "pawbridge_store", "business_constraint")),
            Arguments.of(constraint("23514", "other_schema", "ck_rollback_charset_orders")),
            Arguments.of(constraint("23505", "pawbridge_store", "ck_rollback_charset_orders")),
            Arguments.of(SQLException("ck_rollback_charset_orders private_fixture_value", "23514")),
        )
    }
}
