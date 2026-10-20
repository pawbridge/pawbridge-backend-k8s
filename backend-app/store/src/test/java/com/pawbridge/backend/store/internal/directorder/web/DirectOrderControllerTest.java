package com.pawbridge.backend.store.internal.directorder.web;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.pawbridge.backend.store.internal.directorder.application.DirectOrders;
import com.pawbridge.backend.store.internal.directorder.application.OrderActorRejectedException;
import java.time.LocalDateTime;
import java.sql.BatchUpdateException;
import java.sql.SQLException;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.postgresql.util.PSQLException;
import org.postgresql.util.ServerErrorMessage;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class DirectOrderControllerTest {
    private final DirectOrders orders=mock(DirectOrders.class);
    private final ObjectMapper json=new ObjectMapper().registerModule(new JavaTimeModule())
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    private final DirectOrders.Command command=new DirectOrders.Command(80L,2,"fixture receiver","fixture phone","fixture address","fixture message");
    private final String request="{\"skuId\":80,\"quantity\":2,\"receiverName\":\"fixture receiver\",\"receiverPhone\":\"fixture phone\",\"deliveryAddress\":\"fixture address\",\"deliveryMessage\":\"fixture message\",\"userId\":99}";
    private final DirectOrders.OrderDetails response=new DirectOrders.OrderDetails(9007199254740993L,"fixture-uuid",7L,9007199254740993L,"PENDING","READY",
            "fixture receiver","fixture phone","fixture address","fixture message",LocalDateTime.of(2026,10,20,22,0,1,123456789),
            List.of(new DirectOrders.Item("fixture product","CURRENT-80",9007199254740993L,2)));
    private MockMvc mvc;
    @BeforeEach void setup() {
        SecurityContextHolder.clearContext();
        mvc=MockMvcBuilders.standaloneSetup(new DirectOrderController(orders)).setControllerAdvice(new DirectOrderExceptionHandler())
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(json)).build();
    }
    @AfterEach void clear(){SecurityContextHolder.clearContext();}
    private void current(Object member){SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(member,"unused",List.of()));}
    private void fullResponse(byte[] bytes) throws Exception {
        var node=json.readTree(bytes);assertEquals(12,node.size());assertFalse(node.has("data"));
        assertEquals(json.readTree(json.writeValueAsBytes(response)),node);assertEquals(4,node.get("items").get(0).size());
        assertEquals(9007199254740993L,node.get("orderId").longValue());assertEquals(9007199254740993L,node.get("items").get(0).get("price").longValue());
        assertEquals("2026-10-20T22:00:01.123456789",node.get("createdAt").asText());
    }

    @Test void given__verified_long_member_and_forged_user_sources__when__buying__then__pass_full_request_with_verified_member_and_return_original_response() throws Exception {
        current(7L);when(orders.create(7,command)).thenReturn(response);
        fullResponse(mvc.perform(post("/api/v1/orders/direct").param("userId","99").header("X-User-Id","99")
                .contentType(MediaType.APPLICATION_JSON).content(request)).andExpect(status().isCreated()).andReturn().getResponse().getContentAsByteArray());
        verify(orders).create(7,command);verifyNoMoreInteractions(orders);
    }

    @Test void given__verified_long_member_and_forged_user_query__when__reading__then__use_only_verified_member_and_return_full_order() throws Exception {
        current(7L);when(orders.get(7,9007199254740993L)).thenReturn(response);
        fullResponse(mvc.perform(get("/api/v1/orders/9007199254740993").param("userId","99").header("X-User-Id","99"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray());
        verify(orders).get(7,9007199254740993L);verifyNoMoreInteractions(orders);
    }

    static Stream<Arguments> invalidPrincipals(){return Stream.of("missing","text").flatMap(kind->Stream.of("POST","GET").map(method->Arguments.of(kind,method)));}
    @ParameterizedTest @MethodSource("invalidPrincipals")
    void given__missing_or_non_long_principal_with_forged_user__when__accessing__then__unauthorized_before_application(String kind,String method) throws Exception {
        if(kind.equals("text"))current("99");
        var call=method.equals("POST")?post("/api/v1/orders/direct").contentType(MediaType.APPLICATION_JSON).content(request):get("/api/v1/orders/33");
        mvc.perform(call.param("userId","99").header("X-User-Id","99")).andExpect(status().isUnauthorized()).andExpect(content().string(""));
        verifyNoInteractions(orders);
    }

    static Stream<Arguments> invalidRequests(){return Stream.of(
            Arguments.of("{\"skuId\":80,\"quantity\":0}","quantity","0"),Arguments.of("{\"skuId\":80,\"quantity\":-1}","quantity","-1"),
            Arguments.of("{\"skuId\":0,\"quantity\":2}","skuId","0"),Arguments.of("{\"quantity\":2}","skuId",""),Arguments.of("{\"skuId\":80}","quantity",""));}
    @ParameterizedTest @MethodSource("invalidRequests")
    void given__invalid_sku_or_quantity__when__buying__then__preserve_original_field_value_reason_errors_without_application(String body,String field,String value) throws Exception {
        current(7L);var bytes=mvc.perform(post("/api/v1/orders/direct").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value("Invalid Input Value")).andExpect(jsonPath("$.code").value("C001"))
                .andReturn().getResponse().getContentAsByteArray();
        var errors=json.readTree(bytes).get("errors");assertEquals(1,errors.size());var error=errors.get(0);assertEquals(3,error.size());
        assertEquals(field,error.get("field").asText());assertEquals(value,error.get("value").asText());assertFalse(error.get("reason").asText().isBlank());
        verifyNoInteractions(orders);
    }

    @ParameterizedTest @ValueSource(strings={"bad","9223372036854775808"})
    void given__unparseable_order_id__when__reading__then__preserve_original_type_error_without_application(String orderId) throws Exception {
        current(7L);mvc.perform(get("/api/v1/orders/{orderId}",orderId)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("C005")).andExpect(jsonPath("$.errors[0].field").value("orderId"))
                .andExpect(jsonPath("$.errors[0].value").value(orderId)).andExpect(jsonPath("$.errors[0].reason").value("typeMismatch"));
        verifyNoInteractions(orders);
    }

    static Stream<Arguments> failures(){return Stream.of(
            Arguments.of(new OrderActorRejectedException(),403,"C006","Access is Denied"),
            Arguments.of(new IllegalArgumentException("private account and shipping"),400,"C001","Invalid Input Value"),
            Arguments.of(new org.springframework.dao.DataAccessResourceFailureException("private account and shipping"),500,"C004","Server Error"),
            Arguments.of(new org.springframework.transaction.TransactionSystemException("private account and shipping"),500,"C004","Server Error"));}
    @ParameterizedTest @MethodSource("failures")
    void given__actor_or_order_or_transaction_failure__when__buying__then__preserve_status_and_sanitized_body(RuntimeException failure,int statusCode,String code,String message) throws Exception {
        current(7L);when(orders.create(7,command)).thenThrow(failure);
        String body=mvc.perform(post("/api/v1/orders/direct").contentType(MediaType.APPLICATION_JSON).content(request)).andExpect(status().is(statusCode))
                .andExpect(jsonPath("$.code").value(code)).andExpect(jsonPath("$.message").value(message)).andExpect(jsonPath("$.errors").isEmpty())
                .andReturn().getResponse().getContentAsString();
        assertFalse(body.contains("private"));assertFalse(body.contains("fixture"));
    }

    @Test void given__order_not_accessible_to_member__when__reading__then__preserve_source_bad_input_without_order_details() throws Exception {
        current(7L);when(orders.get(7,33)).thenThrow(new IllegalArgumentException("private owner"));
        mvc.perform(get("/api/v1/orders/33")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("C001"))
                .andExpect(jsonPath("$.errors").isEmpty());
    }

    private static PSQLException constraint(String state,String schema,String name) {
        return new PSQLException(new ServerErrorMessage("SERROR\0C"+state+"\0Mprivate_fixture_value\0Dprivate_fixture_row\0s"+schema+"\0n"+name+"\0"));
    }
    static Stream<Arguments> rollbackCharsetFailures() {
        var violation=constraint("23514","pawbridge_store","ck_rollback_charset_orders");
        var batch=new BatchUpdateException();batch.setNextException(violation);
        return Stream.of(Arguments.of(new org.springframework.dao.DataIntegrityViolationException("private fixture",violation)),
                Arguments.of(new org.springframework.transaction.TransactionSystemException("private commit",violation)),
                Arguments.of(new org.springframework.dao.DataIntegrityViolationException("private batch",batch)));
    }
    @ParameterizedTest @MethodSource("rollbackCharsetFailures")
    void given__rollback_charset_constraint_in_write_or_commit_or_batch__when__buying__then__original_c007_without_private_sql_details(RuntimeException failure) throws Exception {
        current(7L);when(orders.create(7,command)).thenThrow(failure);
        String body=mvc.perform(post("/api/v1/orders/direct").contentType(MediaType.APPLICATION_JSON).content(request))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("C007"))
                .andExpect(jsonPath("$.message").value("일부 이모지 등 지원하지 않는 문자를 제외해 주세요."))
                .andExpect(jsonPath("$.errors").isEmpty()).andReturn().getResponse().getContentAsString();
        assertFalse(body.contains("private"));assertFalse(body.contains("ck_rollback_charset_"));assertFalse(body.contains("23514"));
    }
    static Stream<Arguments> unrelatedConstraintFailures() {
        return Stream.of(Arguments.of(constraint("23514","pawbridge_store","business_constraint")),
                Arguments.of(constraint("23514","other_schema","ck_rollback_charset_orders")),
                Arguments.of(constraint("23505","pawbridge_store","ck_rollback_charset_orders")),
                Arguments.of(new SQLException("ck_rollback_charset_orders private_fixture_value","23514")));
    }
    @ParameterizedTest @MethodSource("unrelatedConstraintFailures")
    void given__other_constraint_or_schema_or_sqlstate_or_unstructured_text__when__buying__then__keep_server_error_without_private_sql_details(SQLException cause) throws Exception {
        current(7L);when(orders.create(7,command)).thenThrow(new org.springframework.dao.DataIntegrityViolationException("private fixture",cause));
        String body=mvc.perform(post("/api/v1/orders/direct").contentType(MediaType.APPLICATION_JSON).content(request))
                .andExpect(status().isInternalServerError()).andExpect(jsonPath("$.code").value("C004"))
                .andExpect(jsonPath("$.message").value("Server Error")).andExpect(jsonPath("$.errors").isEmpty())
                .andReturn().getResponse().getContentAsString();
        assertFalse(body.contains("private"));assertFalse(body.contains("ck_rollback_charset_"));assertFalse(body.contains("23514"));
    }

    @ParameterizedTest @ValueSource(strings={"/api/v1/orders/uuid/fixture","/api/v1/orders/33/payment","/api/v1/orders/cart/direct"})
    void given__neighboring_unapproved_flow__when__requesting__then__no_direct_order_handler(String path) throws Exception {
        current(7L);mvc.perform(get(path)).andExpect(status().isNotFound());verifyNoInteractions(orders);
    }
}
