package com.pawbridge.backend.store.internal.wishlist.web;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.pawbridge.backend.store.internal.wishlist.application.WishlistCommands;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class WishlistCommandControllerTest {
    private final WishlistCommands commands=mock(WishlistCommands.class);
    private final ObjectMapper json=new ObjectMapper().registerModule(new JavaTimeModule())
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    private MockMvc mvc;
    @BeforeEach void setup() {
        SecurityContextHolder.clearContext();
        mvc=MockMvcBuilders.standaloneSetup(new WishlistCommandController(commands))
                .setControllerAdvice(new WishlistCommandExceptionHandler()).setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(json)).build();
    }
    @AfterEach void clear() { SecurityContextHolder.clearContext(); }
    private void current(Object member) { SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(member,"unused",List.of())); }

    @Test void given__authenticated_member_and_forged_body_or_header_user__when__adding__then__use_principal_and_keep_raw_created_response() throws Exception {
        current(7L);when(commands.add(7,80L)).thenReturn(new WishlistCommands.Added(9007199254740993L,7L,80L,"SKU",9L,"상품",null,
                9007199254740993L,Map.of("색상","파랑"),LocalDateTime.of(2026,10,20,9,0,1,123456789)));
        byte[] body=mvc.perform(post("/api/v1/wishlists").header("X-User-Id","99").contentType(MediaType.APPLICATION_JSON)
                .content("{\"skuId\":80,\"userId\":99}")).andExpect(status().isCreated()).andReturn().getResponse().getContentAsByteArray();
        var node=json.readTree(body);assertEquals(10,node.size());assertFalse(node.has("data"));
        assertEquals(7L,node.get("userId").longValue());assertEquals(9007199254740993L,node.get("wishlistId").longValue());
        assertEquals(9007199254740993L,node.get("price").longValue());assertTrue(node.get("productImageUrl").isNull());
        assertEquals("파랑",node.get("options").get("색상").asText());assertEquals("2026-10-20T09:00:01.123456789",node.get("createdAt").asText());
        verify(commands).add(7,80L);
    }

    @Test void given__authenticated_member_and_forged_query_user__when__removing_sku__then__use_principal_and_return_no_content() throws Exception {
        current(7L);mvc.perform(delete("/api/v1/wishlists").param("userId","99").param("skuId","80").header("X-User-Id","99"))
                .andExpect(status().isNoContent()).andExpect(content().string(""));verify(commands).removeByMemberAndSku(7,80);
    }

    @ParameterizedTest @ValueSource(strings={"POST","DELETE"})
    void given__no_principal_and_forged_user_header__when__commanding__then__unauthorized_without_application(String method) throws Exception {
        var request=method.equals("POST")?post("/api/v1/wishlists").contentType(MediaType.APPLICATION_JSON).content("{\"skuId\":80,\"userId\":99}")
                :delete("/api/v1/wishlists").param("skuId","80").param("userId","99");
        mvc.perform(request.header("X-User-Id","99")).andExpect(status().isUnauthorized());verifyNoInteractions(commands);
    }

    @Test void given__non_long_principal__when__adding__then__reject_without_interpreting_user_text() throws Exception {
        current("99");mvc.perform(post("/api/v1/wishlists").contentType(MediaType.APPLICATION_JSON).content("{\"skuId\":80}"))
                .andExpect(status().isUnauthorized());verifyNoInteractions(commands);
    }

    @ParameterizedTest @ValueSource(longs={33L,9007199254740993L})
    void given__authenticated_member_and_forged_user_sources__when__removing_identity__then__use_only_principal_and_return_no_content(long wishlistId) throws Exception {
        current(7L);
        mvc.perform(delete("/api/v1/wishlists/{wishlistId}",wishlistId).param("userId","99").header("X-User-Id","99")
                .contentType(MediaType.APPLICATION_JSON).content("{\"userId\":99}"))
                .andExpect(status().isNoContent()).andExpect(content().string(""));
        verify(commands).removeById(7,wishlistId);verifyNoMoreInteractions(commands);
    }

    @ParameterizedTest @ValueSource(strings={"missing","text"})
    void given__missing_or_non_long_principal__when__removing_identity_with_forged_user__then__unauthorized_without_command(String principal) throws Exception {
        if(principal.equals("text")) current("99");
        mvc.perform(delete("/api/v1/wishlists/33").param("userId","99").header("X-User-Id","99"))
                .andExpect(status().isUnauthorized()).andExpect(content().string(""));
        verifyNoInteractions(commands);
    }

    @ParameterizedTest @ValueSource(strings={"bad","9223372036854775808","33.0"})
    void given__unparseable_identity__when__removing_identity__then__bad_type_without_command(String wishlistId) throws Exception {
        current(7L);
        mvc.perform(delete("/api/v1/wishlists/{wishlistId}",wishlistId)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("C005")).andExpect(jsonPath("$.errors[0].field").value("wishlistId"))
                .andExpect(jsonPath("$.errors[0].value").value(wishlistId)).andExpect(jsonPath("$.errors[0].reason").value("typeMismatch"));
        verifyNoInteractions(commands);
    }

    @ParameterizedTest @MethodSource("identityFailures")
    void given__database_or_transaction_failure__when__removing_identity__then__return_server_error_without_private_details(RuntimeException failure) throws Exception {
        current(7L);doThrow(failure).when(commands).removeById(7,33);
        String body=mvc.perform(delete("/api/v1/wishlists/33")).andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("C004")).andExpect(jsonPath("$.message").value("Server Error"))
                .andReturn().getResponse().getContentAsString();
        assertFalse(body.contains("private delete"));verify(commands).removeById(7,33);verifyNoMoreInteractions(commands);
    }
    static Stream<Arguments> identityFailures() { return Stream.of(
            Arguments.of(new org.springframework.dao.DataAccessResourceFailureException("private delete SQL")),
            Arguments.of(new org.springframework.transaction.CannotCreateTransactionException("private delete transaction"))); }

    @ParameterizedTest @MethodSource("addFailures")
    void given__source_command_failure__when__adding__then__preserve_status_code_and_hide_private_details(RuntimeException failure,int statusCode,String code) throws Exception {
        current(7L);when(commands.add(7,80L)).thenThrow(failure);
        mvc.perform(post("/api/v1/wishlists").contentType(MediaType.APPLICATION_JSON).content("{\"skuId\":80}"))
                .andExpect(status().is(statusCode)).andExpect(jsonPath("$.code").value(code)).andExpect(jsonPath("$.errors").isEmpty())
                .andExpect(jsonPath("$.message").value(statusCode==400?"Invalid Input Value":"Server Error"));
    }
    static Stream<Arguments> addFailures() { return Stream.of(Arguments.of(new IllegalStateException("이미 찜한 상품입니다."),500,"C004"),
            Arguments.of(new IllegalArgumentException("SKU를 찾을 수 없습니다: 80"),400,"C001"),Arguments.of(new RuntimeException("private SQL detail"),500,"C004")); }

    @Test void given__missing_pair__when__removing_sku__then__preserve_bad_input_error() throws Exception {
        current(7L);doThrow(new IllegalArgumentException("찜을 찾을 수 없습니다.")).when(commands).removeByMemberAndSku(7,80);
        mvc.perform(delete("/api/v1/wishlists").param("skuId","80")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("C001"));
    }

    @Test void given__wrong_sku_query_type__when__deleting__then__original_field_error_before_command() throws Exception {
        current(7L);mvc.perform(delete("/api/v1/wishlists").param("skuId","bad")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("C005")).andExpect(jsonPath("$.errors[0].field").value("skuId"))
                .andExpect(jsonPath("$.errors[0].value").value("bad")).andExpect(jsonPath("$.errors[0].reason").value("typeMismatch"));
        verifyNoInteractions(commands);
    }
}
