package com.pawbridge.backend.store.internal.directorder.web

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.pawbridge.backend.store.internal.directorder.application.CartOrders
import com.pawbridge.backend.store.internal.directorder.application.DirectOrders
import com.pawbridge.backend.store.internal.directorder.application.OrderActorRejectedException
import io.mockk.confirmVerified
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import org.junit.jupiter.params.provider.ValueSource
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
    val orders = mockk<CartOrders>()
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
    fun `검증된 회원과 배송 필드가 있을 때__장바구니를 주문하면__원본 그대로의 201 주문 상세를 돌려주고 클라이언트의 장바구니나 사용자 덮어쓰기를 무시한다`() {
        // given
        current(7L)

        every { orders.create(any(), any()) } returns response

        // when
        val body = mvc.perform(
            post("/api/v1/orders").header("X-User-Id", "99").param("userId", "99")
                .contentType(MediaType.APPLICATION_JSON).content(request),
        ).andExpect(status().isCreated()).andReturn().response.contentAsByteArray

        // then
        val node = json.readTree(body)
        assertEquals(12, node.size())
        assertEquals(4, node.get("items").get(0).size())
        assertEquals(json.readTree(json.writeValueAsBytes(response)), node)
        assertEquals(9007199254740993L, node.get("orderId").longValue())
        assertEquals("2026-10-21T02:00:01.123456789", node.get("createdAt").asText())
        verify(exactly = 1) { orders.create(7, command) }
        confirmVerified(orders)
    }

    @Test
    fun `원본에 검증이 없는 배송 정보일 때__요청을 매핑하면__새 NotBlank나 Size 규칙 없이 null을 그대로 넘긴다`() {
        // given
        current(7L)
        val empty = CartOrders.Command(null, null, null, null)

        every { orders.create(any(), any()) } returns response

        // when
        mvc.perform(post("/api/v1/orders").contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isCreated())

        // then
        verify(exactly = 1) { orders.create(7, empty) }
        confirmVerified(orders)
    }

    @ParameterizedTest
    @ValueSource(strings = ["missing", "text"])
    fun `주체가 없거나 Long이 아닐 때__장바구니를 주문하면__애플리케이션 호출 전에 401을 돌려준다`(kind: String) {
        // given
        if (kind == "text") {
            current("99")
        }

        // when
        val result = mvc.perform(
            post("/api/v1/orders").header("X-User-Id", "99").contentType(MediaType.APPLICATION_JSON).content(request),
        )

        // then
        result.andExpect(status().isUnauthorized()).andExpect(content().string(""))
        confirmVerified(orders)
    }

    @ParameterizedTest
    @MethodSource("failures")
    fun `행위자 검증이나 영속화 실패가 나면__장바구니를 주문하면__개인 정보 없이 기존 범위 오류 계약을 지킨다`(
        failure: RuntimeException,
        statusCode: Int,
        code: String,
    ) {
        // given
        current(7L)

        every { orders.create(any(), any()) } throws failure

        // when
        val body = mvc.perform(post("/api/v1/orders").contentType(MediaType.APPLICATION_JSON).content(request))
            .andExpect(status().`is`(statusCode))
            .andExpect(jsonPath("\$.code").value(code))
            .andExpect(jsonPath("\$.errors").isEmpty()).andReturn().response.contentAsString

        // then
        assertFalse(body.contains("private"))
        verify(exactly = 1) { orders.create(7, command) }
        confirmVerified(orders)
    }

    @Test
    fun `주문 문자셋 제약을 어길 때__장바구니 주문을 flush하면__행이나 SQL 상세 없이 기존 C007을 돌려준다`() {
        // given
        current(7L)
        val sql = PSQLException(
            ServerErrorMessage(
                "SERROR\u0000C23514\u0000Mprivate_fixture\u0000Dprivate_row\u0000spawbridge_store\u0000nck_rollback_charset_orders\u0000",
            ),
        )

        every { orders.create(any(), any()) } throws DataIntegrityViolationException("private SQL", sql)

        // when
        val body = mvc.perform(post("/api/v1/orders").contentType(MediaType.APPLICATION_JSON).content(request))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("\$.code").value("C007")).andReturn().response.contentAsString

        // then
        assertFalse(body.contains("private"))
        assertFalse(body.contains("ck_rollback_charset_"))
        verify(exactly = 1) { orders.create(7, command) }
        confirmVerified(orders)
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
