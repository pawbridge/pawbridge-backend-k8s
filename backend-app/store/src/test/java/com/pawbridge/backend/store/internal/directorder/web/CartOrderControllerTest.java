package com.pawbridge.backend.store.internal.directorder.web;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.pawbridge.backend.store.internal.directorder.application.CartOrders;
import com.pawbridge.backend.store.internal.directorder.application.DirectOrders;
import com.pawbridge.backend.store.internal.directorder.application.OrderActorRejectedException;
import java.time.LocalDateTime;
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

class CartOrderControllerTest {
    final CartOrders orders=mock(CartOrders.class);
    final ObjectMapper json=new ObjectMapper().registerModule(new JavaTimeModule()).disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS).disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    final CartOrders.Command command=new CartOrders.Command("receiver","phone","address","message");
    final String request="{\"receiverName\":\"receiver\",\"receiverPhone\":\"phone\",\"deliveryAddress\":\"address\",\"deliveryMessage\":\"message\",\"userId\":99,\"skuId\":999,\"quantity\":999}";
    final DirectOrders.OrderDetails response=new DirectOrders.OrderDetails(9007199254740993L,"fixture-uuid",7L,9007199254740993L,"PENDING","READY","receiver","phone","address","message",
            LocalDateTime.of(2026,10,21,2,0,1,123456789),List.of(new DirectOrders.Item("product","SKU-80",9007199254740993L,1)));
    MockMvc mvc;
    @BeforeEach void setup() {
        SecurityContextHolder.clearContext();mvc=MockMvcBuilders.standaloneSetup(new CartOrderController(orders))
                .setControllerAdvice(new DirectOrderExceptionHandler()).setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(json)).build();
    }
    @AfterEach void clear(){SecurityContextHolder.clearContext();}
    void current(Object user){SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(user,"unused",List.of()));}
    @Test void given__verified_member_and_shipping_fields__when__checking_out__then__raw_original_201_order_details_and_no_client_cart_or_user_override() throws Exception {
        current(7L);when(orders.create(7,command)).thenReturn(response);
        byte[] body=mvc.perform(post("/api/v1/orders").header("X-User-Id","99").param("userId","99").contentType(MediaType.APPLICATION_JSON).content(request))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsByteArray();
        var node=json.readTree(body);assertEquals(12,node.size());assertEquals(4,node.get("items").get(0).size());assertEquals(json.readTree(json.writeValueAsBytes(response)),node);
        assertEquals(9007199254740993L,node.get("orderId").longValue());assertEquals("2026-10-21T02:00:01.123456789",node.get("createdAt").asText());
        verify(orders).create(7,command);verifyNoMoreInteractions(orders);
    }
    @Test void given__original_unvalidated_shipping__when__mapping_request__then__forward_nulls_without_new_notblank_or_size_rules() throws Exception {
        current(7L);var empty=new CartOrders.Command(null,null,null,null);when(orders.create(7,empty)).thenReturn(response);
        mvc.perform(post("/api/v1/orders").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isCreated());verify(orders).create(7,empty);verifyNoMoreInteractions(orders);
    }
    @ParameterizedTest @ValueSource(strings={"missing","text"})
    void given__missing_or_non_long_principal__when__checking_out__then__unauthorized_before_application(String kind) throws Exception {
        if(kind.equals("text"))current("99");mvc.perform(post("/api/v1/orders").header("X-User-Id","99").contentType(MediaType.APPLICATION_JSON).content(request))
                .andExpect(status().isUnauthorized()).andExpect(content().string(""));verifyNoInteractions(orders);
    }
    static Stream<Arguments> failures(){return Stream.of(Arguments.of(new OrderActorRejectedException(),403,"C006"),Arguments.of(new IllegalArgumentException("private empty or overflow"),400,"C001"),
            Arguments.of(new org.springframework.dao.DataAccessResourceFailureException("private SQL"),500,"C004"),Arguments.of(new org.springframework.transaction.TransactionSystemException("private commit"),500,"C004"));}
    @ParameterizedTest @MethodSource("failures")
    void given__actor_validation_or_persistence_failure__when__checking_out__then__existing_scoped_error_contract_without_private_details(RuntimeException failure,int statusCode,String code) throws Exception {
        current(7L);when(orders.create(7,command)).thenThrow(failure);
        String body=mvc.perform(post("/api/v1/orders").contentType(MediaType.APPLICATION_JSON).content(request)).andExpect(status().is(statusCode))
                .andExpect(jsonPath("$.code").value(code)).andExpect(jsonPath("$.errors").isEmpty()).andReturn().getResponse().getContentAsString();
        assertFalse(body.contains("private"));
    }
    @Test void given__original_orders_charset_constraint__when__flushing_checkout__then__existing_c007_without_row_or_sql_details() throws Exception {
        current(7L);var sql=new PSQLException(new ServerErrorMessage("SERROR\0C23514\0Mprivate_fixture\0Dprivate_row\0spawbridge_store\0nck_rollback_charset_orders\0"));
        when(orders.create(7,command)).thenThrow(new org.springframework.dao.DataIntegrityViolationException("private SQL",sql));
        String body=mvc.perform(post("/api/v1/orders").contentType(MediaType.APPLICATION_JSON).content(request)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("C007")).andReturn().getResponse().getContentAsString();assertFalse(body.contains("private"));assertFalse(body.contains("ck_rollback_charset_"));
    }
}
