package com.pawbridge.backend.store.internal.cart.web

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.pawbridge.backend.store.internal.cart.application.CartActorRejectedException
import com.pawbridge.backend.store.internal.cart.application.Carts
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
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.verifyNoMoreInteractions
import org.springframework.dao.DataAccessResourceFailureException
import org.springframework.http.MediaType
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.GrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.transaction.TransactionSystemException
import java.util.stream.Stream

class CartControllerTest {
    val carts: Carts = mock(Carts::class.java)
    val json: ObjectMapper = ObjectMapper().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
    lateinit var mvc: MockMvc

    @BeforeEach
    fun setup() {
        SecurityContextHolder.clearContext()
        mvc = MockMvcBuilders.standaloneSetup(CartController(carts)).setControllerAdvice(CartExceptionHandler())
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
    fun given__verified_member__when__reading__then__raw_seven_field_array_with_null_id_and_exact_long_values() {
        current(7L)
        `when`(carts.get(7)).thenReturn(
            listOf(Carts.Item(null, 80L, "LIVE-80", "live product", 9007199254740993L, 2, 18014398509481986L)),
        )

        val body = mvc.perform(request("get").header("X-User-Id", "99").param("userId", "99"))
            .andExpect(status().isOk()).andReturn().response.contentAsByteArray

        val node = json.readTree(body)
        assertTrue(node.isArray)
        assertEquals(1, node.size())
        val item = node.get(0)
        assertEquals(7, item.size())
        assertTrue(item.get("id").isNull)
        assertEquals(80L, item.get("skuId").longValue())
        assertEquals("LIVE-80", item.get("skuCode").asText())
        assertEquals("live product", item.get("productName").asText())
        assertEquals(9007199254740993L, item.get("price").longValue())
        assertEquals(2, item.get("quantity").intValue())
        assertEquals(18014398509481986L, item.get("totalPrice").longValue())
        verify(carts).get(7)
        verifyNoMoreInteractions(carts)
    }

    @Test
    fun given__empty_cart__when__reading__then__empty_array() {
        current(7L)
        `when`(carts.get(7)).thenReturn(listOf())

        mvc.perform(request("get")).andExpect(status().isOk()).andExpect(content().json("[]"))
    }

    @ParameterizedTest
    @ValueSource(strings = ["add", "update", "remove", "clear"])
    fun given__verified_member_with_forged_user_sources__when__mutating__then__only_principal_and_original_200_empty(
        operation: String,
    ) {
        current(7L)

        mvc.perform(request(operation).header("X-User-Id", "99").param("userId", "99"))
            .andExpect(status().isOk()).andExpect(content().string(""))

        when (operation) {
            "add" -> verify(carts).add(7, 80L, 2)
            "update" -> verify(carts).update(7, 80, -1)
            "remove" -> verify(carts).remove(7, 80)
            else -> verify(carts).clear(7)
        }
        verifyNoMoreInteractions(carts)
    }

    @ParameterizedTest
    @MethodSource("invalidPrincipals")
    fun given__missing_or_non_long_principal__when__accessing_any_cart_endpoint__then__unauthorized_before_application(
        kind: String,
        operation: String,
    ) {
        if (kind == "text") {
            current("99")
        }

        mvc.perform(request(operation).header("X-User-Id", "99").param("userId", "99"))
            .andExpect(status().isUnauthorized()).andExpect(content().string(""))

        verifyNoInteractions(carts)
    }

    @ParameterizedTest
    @ValueSource(strings = ["quantity", "skuId"])
    fun given__invalid_integer_argument__when__patching__then__original_type_error_without_application(field: String) {
        current(7L)
        val call = if (field == "skuId") {
            patch("/api/v1/carts/items/bad").param("quantity", "1")
        } else {
            patch("/api/v1/carts/items/80").param("quantity", "2147483648")
        }

        mvc.perform(call).andExpect(status().isBadRequest())
            .andExpect(jsonPath("\$.code").value("C005"))
            .andExpect(jsonPath("\$.errors[0].field").value(field))
            .andExpect(jsonPath("\$.errors[0].reason").value("typeMismatch"))

        verifyNoInteractions(carts)
    }

    @Test
    fun given__missing_quantity__when__patching__then__bad_input_before_application() {
        current(7L)

        mvc.perform(patch("/api/v1/carts/items/80")).andExpect(status().isBadRequest())
            .andExpect(jsonPath("\$.code").value("C001"))

        verifyNoInteractions(carts)
    }

    @ParameterizedTest
    @MethodSource("failures")
    fun given__actor_or_validation_or_persistence_failure__when__adding__then__sanitized_error_without_success(
        failure: RuntimeException,
        statusCode: Int,
        code: String,
    ) {
        current(7L)
        doThrow(failure).`when`(carts).add(7, 80L, 2)

        val body = mvc.perform(request("add")).andExpect(status().`is`(statusCode))
            .andExpect(jsonPath("\$.code").value(code))
            .andExpect(jsonPath("\$.errors").isEmpty()).andReturn().response.contentAsString

        assertFalse(body.contains("private"))
    }

    @Test
    fun given__reset_path__when__deleting__then__no_customer_reset_handler() {
        current(7L)

        mvc.perform(delete("/api/v1/carts/reset")).andExpect(status().isNotFound())

        verifyNoInteractions(carts)
    }

    companion object {
        fun request(operation: String): MockHttpServletRequestBuilder {
            return when (operation) {
                "get" -> get("/api/v1/carts")
                "add" -> post("/api/v1/carts/items").contentType(MediaType.APPLICATION_JSON)
                    .content("{\"skuId\":80,\"quantity\":2,\"userId\":99}")
                "update" -> patch("/api/v1/carts/items/80").param("quantity", "-1")
                "remove" -> delete("/api/v1/carts/items/80")
                else -> delete("/api/v1/carts")
            }
        }

        @JvmStatic
        fun invalidPrincipals(): Stream<Arguments> {
            return Stream.of("missing", "text").flatMap { kind ->
                Stream.of("get", "add", "update", "remove", "clear").map { op -> Arguments.of(kind, op) }
            }
        }

        @JvmStatic
        fun failures(): Stream<Arguments> = Stream.of(
            Arguments.of(CartActorRejectedException(), 403, "C006"),
            Arguments.of(IllegalArgumentException("private overflow"), 400, "C001"),
            Arguments.of(DataAccessResourceFailureException("private SQL"), 500, "C004"),
            Arguments.of(TransactionSystemException("private commit"), 500, "C004"),
        )
    }
}
