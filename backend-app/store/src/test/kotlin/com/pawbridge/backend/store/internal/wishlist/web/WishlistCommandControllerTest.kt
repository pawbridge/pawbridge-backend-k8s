package com.pawbridge.backend.store.internal.wishlist.web

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.pawbridge.backend.store.internal.wishlist.application.WishlistCommands
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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.transaction.CannotCreateTransactionException
import java.time.LocalDateTime
import java.util.stream.Stream

class WishlistCommandControllerTest {
    private val commands: WishlistCommands = mock(WishlistCommands::class.java)
    private val json: ObjectMapper = ObjectMapper().registerModule(JavaTimeModule())
        .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
    private lateinit var mvc: MockMvc

    @BeforeEach
    fun setup() {
        SecurityContextHolder.clearContext()
        mvc = MockMvcBuilders.standaloneSetup(WishlistCommandController(commands))
            .setControllerAdvice(WishlistCommandExceptionHandler())
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

    @Test
    fun given__authenticated_member_and_forged_body_or_header_user__when__adding__then__use_principal_and_keep_raw_created_response() {
        current(7L)
        `when`(commands.add(7, 80L)).thenReturn(
            WishlistCommands.Added(
                9007199254740993L, 7L, 80L, "SKU", 9L, "상품", null,
                9007199254740993L, mutableMapOf("색상" to "파랑"), LocalDateTime.of(2026, 10, 20, 9, 0, 1, 123456789),
            ),
        )

        val body = mvc.perform(
            post("/api/v1/wishlists").header("X-User-Id", "99").contentType(MediaType.APPLICATION_JSON)
                .content("{\"skuId\":80,\"userId\":99}"),
        ).andExpect(status().isCreated()).andReturn().response.contentAsByteArray

        val node = json.readTree(body)
        assertEquals(10, node.size())
        assertFalse(node.has("data"))
        assertEquals(7L, node.get("userId").longValue())
        assertEquals(9007199254740993L, node.get("wishlistId").longValue())
        assertEquals(9007199254740993L, node.get("price").longValue())
        assertTrue(node.get("productImageUrl").isNull)
        assertEquals("파랑", node.get("options").get("색상").asText())
        assertEquals("2026-10-20T09:00:01.123456789", node.get("createdAt").asText())
        verify(commands).add(7, 80L)
    }

    @Test
    fun given__authenticated_member_and_forged_query_user__when__removing_sku__then__use_principal_and_return_no_content() {
        current(7L)

        mvc.perform(delete("/api/v1/wishlists").param("userId", "99").param("skuId", "80").header("X-User-Id", "99"))
            .andExpect(status().isNoContent()).andExpect(content().string(""))

        verify(commands).removeByMemberAndSku(7, 80)
    }

    @ParameterizedTest
    @ValueSource(strings = ["POST", "DELETE"])
    fun given__no_principal_and_forged_user_header__when__commanding__then__unauthorized_without_application(method: String) {
        val request = if (method == "POST") {
            post("/api/v1/wishlists").contentType(MediaType.APPLICATION_JSON).content("{\"skuId\":80,\"userId\":99}")
        } else {
            delete("/api/v1/wishlists").param("skuId", "80").param("userId", "99")
        }

        mvc.perform(request.header("X-User-Id", "99")).andExpect(status().isUnauthorized())

        verifyNoInteractions(commands)
    }

    @Test
    fun given__non_long_principal__when__adding__then__reject_without_interpreting_user_text() {
        current("99")

        mvc.perform(post("/api/v1/wishlists").contentType(MediaType.APPLICATION_JSON).content("{\"skuId\":80}"))
            .andExpect(status().isUnauthorized())

        verifyNoInteractions(commands)
    }

    @ParameterizedTest
    @ValueSource(longs = [33L, 9007199254740993L])
    fun given__authenticated_member_and_forged_user_sources__when__removing_identity__then__use_only_principal_and_return_no_content(
        wishlistId: Long,
    ) {
        current(7L)

        mvc.perform(
            delete("/api/v1/wishlists/{wishlistId}", wishlistId).param("userId", "99").header("X-User-Id", "99")
                .contentType(MediaType.APPLICATION_JSON).content("{\"userId\":99}"),
        ).andExpect(status().isNoContent()).andExpect(content().string(""))

        verify(commands).removeById(7, wishlistId)
        verifyNoMoreInteractions(commands)
    }

    @ParameterizedTest
    @ValueSource(strings = ["missing", "text"])
    fun given__missing_or_non_long_principal__when__removing_identity_with_forged_user__then__unauthorized_without_command(
        principal: String,
    ) {
        if (principal == "text") {
            current("99")
        }

        mvc.perform(delete("/api/v1/wishlists/33").param("userId", "99").header("X-User-Id", "99"))
            .andExpect(status().isUnauthorized()).andExpect(content().string(""))

        verifyNoInteractions(commands)
    }

    @ParameterizedTest
    @ValueSource(strings = ["bad", "9223372036854775808", "33.0"])
    fun given__unparseable_identity__when__removing_identity__then__bad_type_without_command(wishlistId: String) {
        current(7L)

        mvc.perform(delete("/api/v1/wishlists/{wishlistId}", wishlistId)).andExpect(status().isBadRequest())
            .andExpect(jsonPath("\$.code").value("C005"))
            .andExpect(jsonPath("\$.errors[0].field").value("wishlistId"))
            .andExpect(jsonPath("\$.errors[0].value").value(wishlistId))
            .andExpect(jsonPath("\$.errors[0].reason").value("typeMismatch"))

        verifyNoInteractions(commands)
    }

    @ParameterizedTest
    @MethodSource("identityFailures")
    fun given__database_or_transaction_failure__when__removing_identity__then__return_server_error_without_private_details(
        failure: RuntimeException,
    ) {
        current(7L)
        doThrow(failure).`when`(commands).removeById(7, 33)

        val body = mvc.perform(delete("/api/v1/wishlists/33")).andExpect(status().isInternalServerError())
            .andExpect(jsonPath("\$.code").value("C004"))
            .andExpect(jsonPath("\$.message").value("Server Error"))
            .andReturn().response.contentAsString

        assertFalse(body.contains("private delete"))
        verify(commands).removeById(7, 33)
        verifyNoMoreInteractions(commands)
    }

    @ParameterizedTest
    @MethodSource("addFailures")
    fun given__source_command_failure__when__adding__then__preserve_status_code_and_hide_private_details(
        failure: RuntimeException,
        statusCode: Int,
        code: String,
    ) {
        current(7L)
        `when`(commands.add(7, 80L)).thenThrow(failure)

        mvc.perform(post("/api/v1/wishlists").contentType(MediaType.APPLICATION_JSON).content("{\"skuId\":80}"))
            .andExpect(status().`is`(statusCode))
            .andExpect(jsonPath("\$.code").value(code))
            .andExpect(jsonPath("\$.errors").isEmpty())
            .andExpect(jsonPath("\$.message").value(if (statusCode == 400) "Invalid Input Value" else "Server Error"))
    }

    @Test
    fun given__missing_pair__when__removing_sku__then__preserve_bad_input_error() {
        current(7L)
        doThrow(IllegalArgumentException("찜을 찾을 수 없습니다.")).`when`(commands).removeByMemberAndSku(7, 80)

        mvc.perform(delete("/api/v1/wishlists").param("skuId", "80")).andExpect(status().isBadRequest())
            .andExpect(jsonPath("\$.code").value("C001"))
    }

    @Test
    fun given__wrong_sku_query_type__when__deleting__then__original_field_error_before_command() {
        current(7L)

        mvc.perform(delete("/api/v1/wishlists").param("skuId", "bad")).andExpect(status().isBadRequest())
            .andExpect(jsonPath("\$.code").value("C005"))
            .andExpect(jsonPath("\$.errors[0].field").value("skuId"))
            .andExpect(jsonPath("\$.errors[0].value").value("bad"))
            .andExpect(jsonPath("\$.errors[0].reason").value("typeMismatch"))

        verifyNoInteractions(commands)
    }

    companion object {
        @JvmStatic
        fun identityFailures(): Stream<Arguments> = Stream.of(
            Arguments.of(DataAccessResourceFailureException("private delete SQL")),
            Arguments.of(CannotCreateTransactionException("private delete transaction")),
        )

        @JvmStatic
        fun addFailures(): Stream<Arguments> = Stream.of(
            Arguments.of(IllegalStateException("이미 찜한 상품입니다."), 500, "C004"),
            Arguments.of(IllegalArgumentException("SKU를 찾을 수 없습니다: 80"), 400, "C001"),
            Arguments.of(RuntimeException("private SQL detail"), 500, "C004"),
        )
    }
}
