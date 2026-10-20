package com.pawbridge.backend.store.internal.orderadministration.web;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.pawbridge.backend.store.internal.directorder.application.DirectOrders;
import com.pawbridge.backend.store.internal.orderadministration.application.*;
import jakarta.persistence.PersistenceException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.data.web.PageableHandlerMethodArgumentResolver;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class AdminOrderControllerTest {
    final AdminOrders orders=mock(AdminOrders.class);
    final ObjectMapper json=new ObjectMapper().registerModule(new JavaTimeModule()).disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS).disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    final DirectOrders.OrderDetails detail=new DirectOrders.OrderDetails(9007199254740993L,"stored-uuid",88L,9007199254740993L,"PENDING","READY","receiver","phone","address","message",
            LocalDateTime.of(2026,10,21,2,30,1,123456789),List.of(new DirectOrders.Item("stored product","SKU-80",9007199254740993L,1)));
    MockMvc mvc;
    @BeforeEach void setup(){SecurityContextHolder.clearContext();mvc=MockMvcBuilders.standaloneSetup(new AdminOrderController(orders)).setControllerAdvice(new AdminOrderExceptionHandler())
            .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver(),new PageableHandlerMethodArgumentResolver())
            .setMessageConverters(new MappingJackson2HttpMessageConverter(json)).build();}
    @AfterEach void clear(){SecurityContextHolder.clearContext();}
    void current(Object user){SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(user,"unused",List.of()));}
    static MockHttpServletRequestBuilder request(String op){return switch(op){case "list"->get("/api/v1/admin/orders");case "get"->get("/api/v1/admin/orders/33");default->patch("/api/v1/admin/orders/33/delivery-status").contentType(MediaType.APPLICATION_JSON).content("{\"deliveryStatus\":\"SHIPPING\",\"role\":\"ROLE_ADMIN\",\"userId\":99,\"status\":\"PAID\"}");};}
    @Test void given__original_filters_and_sort_page__when__listing__then__original_page_wire_and_verified_caller_with_foreign_user_filter() throws Exception {
        current(7L);var query=new AdminOrders.Query("PAID","SHIPPING",88L," A%_ B ","totalAmount","ASC",2,3);
        when(orders.list(7,query)).thenReturn(new AdminOrders.Page(List.of(detail),2,3,11,"totalAmount",true));
        byte[] bytes=mvc.perform(get("/api/v1/admin/orders").header("X-User-Id","99").header("X-User-Role","ROLE_ADMIN")
                .param("status","PAID").param("deliveryStatus","SHIPPING").param("userId","88").param("keyword"," A%_ B ")
                .param("sortBy","totalAmount").param("sortOrder","ASC").param("page","2").param("size","3"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        var page=json.readTree(bytes);assertFalse(page.has("data"));assertEquals(2,page.get("number").intValue());assertEquals(3,page.get("size").intValue());assertEquals(11,page.get("totalElements").longValue());
        assertEquals(4,page.get("totalPages").intValue());assertFalse(page.get("first").booleanValue());assertFalse(page.get("last").booleanValue());assertEquals(1,page.get("numberOfElements").intValue());
        assertEquals(12,page.get("content").get(0).size());assertEquals(json.readTree(json.writeValueAsBytes(detail)),page.get("content").get(0));
        assertTrue(page.get("sort").get("sorted").booleanValue());verify(orders).list(7,query);verifyNoMoreInteractions(orders);
    }
    @Test void given__default_query__when__listing__then__created_at_desc_default_twenty_and_empty_page() throws Exception {
        current(7L);var query=new AdminOrders.Query(null,null,null,null,"createdAt","desc",0,20);
        when(orders.list(7,query)).thenReturn(new AdminOrders.Page(List.of(),0,20,0,"createdAt",false));
        mvc.perform(request("list")).andExpect(status().isOk()).andExpect(jsonPath("$.content").isEmpty()).andExpect(jsonPath("$.number").value(0)).andExpect(jsonPath("$.size").value(20));verify(orders).list(7,query);
    }
    @Test void given__verified_caller__when__reading_detail__then__raw_original_order_details() throws Exception {
        current(7L);when(orders.get(7,33)).thenReturn(detail);
        var bytes=mvc.perform(request("get").header("X-User-Id","99")).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        assertEquals(json.readTree(json.writeValueAsBytes(detail)),json.readTree(bytes));verify(orders).get(7,33);verifyNoMoreInteractions(orders);
    }
    @ParameterizedTest @ValueSource(strings={"READY","SHIPPING","DELIVERED"})
    void given__delivery_enum_and_forged_other_fields__when__patching__then__only_delivery_and_verified_caller_are_forwarded(String delivery) throws Exception {
        current(7L);when(orders.updateDelivery(7,33,delivery)).thenReturn(detail);
        mvc.perform(patch("/api/v1/admin/orders/33/delivery-status").header("X-User-Id","99").header("X-User-Role","ROLE_ADMIN").contentType(MediaType.APPLICATION_JSON)
                .content("{\"deliveryStatus\":\""+delivery+"\",\"status\":\"PAID\",\"userId\":99}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("PENDING"));verify(orders).updateDelivery(7,33,delivery);verifyNoMoreInteractions(orders);
    }
    static Stream<Arguments> principals(){return Stream.of("missing","text").flatMap(kind->Stream.of("list","get","write").map(op->Arguments.of(kind,op)));}
    @ParameterizedTest @MethodSource("principals")
    void given__missing_or_non_long_principal_with_forged_admin_header__when__using_any_endpoint__then__unauthorized_without_application(String kind,String op) throws Exception {
        if(kind.equals("text"))current("99");mvc.perform(request(op).header("X-User-Id","99").header("X-User-Role","ROLE_ADMIN"))
                .andExpect(status().isUnauthorized()).andExpect(content().string(""));verifyNoInteractions(orders);
    }
    @ParameterizedTest @ValueSource(strings={"list","get","write"})
    void given__current_database_admin_rejected__when__using_any_endpoint__then__forbidden_without_private_details(String op) throws Exception {
        current(7L);var failure=new AdministratorRejectedException();
        if(op.equals("list"))when(orders.list(eq(7L),any())).thenThrow(failure);else if(op.equals("get"))when(orders.get(7,33)).thenThrow(failure);else when(orders.updateDelivery(7,33,"SHIPPING")).thenThrow(failure);
        mvc.perform(request(op)).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("C006"));
    }
    @Test void given__missing_order__when__reading_and_patching__then__original_404_and_400_distinction() throws Exception {
        current(7L);when(orders.get(7,33)).thenThrow(new AdminOrderNotFoundException());when(orders.updateDelivery(7,33,"SHIPPING")).thenThrow(new IllegalArgumentException("private missing id"));
        mvc.perform(request("get")).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("O001")).andExpect(jsonPath("$.message").value("Order not found"));
        mvc.perform(request("write")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("C001"));
    }
    @ParameterizedTest @ValueSource(strings={"status","deliveryStatus","userId"})
    void given__wrong_query_type__when__listing__then__original_field_type_error_before_application(String field) throws Exception {
        current(7L);mvc.perform(get("/api/v1/admin/orders").param(field,"bad")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("C005"))
                .andExpect(jsonPath("$.errors[0].field").value(field)).andExpect(jsonPath("$.errors[0].reason").value("typeMismatch"));verifyNoInteractions(orders);
    }
    @ParameterizedTest @ValueSource(strings={"get","write"})
    void given__wrong_order_id_type__when__accessing__then__original_bad_type_contract(String op) throws Exception {
        current(7L);var call=op.equals("get")?get("/api/v1/admin/orders/bad"):patch("/api/v1/admin/orders/bad/delivery-status").contentType(MediaType.APPLICATION_JSON).content("{\"deliveryStatus\":\"READY\"}");
        mvc.perform(call).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("C005")).andExpect(jsonPath("$.errors[0].field").value("orderId"));verifyNoInteractions(orders);
    }
    @ParameterizedTest @ValueSource(strings={"{}","{\"deliveryStatus\":null}"})
    void given__null_delivery_value__when__patching__then__preserve_original_500_generic_failure_family(String body) throws Exception {
        current(7L);when(orders.updateDelivery(7,33,null)).thenThrow(new IllegalStateException("Delivery status cannot be stored"));
        String response=mvc.perform(patch("/api/v1/admin/orders/33/delivery-status").contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("C004")).andReturn().getResponse().getContentAsString();assertFalse(response.contains("private"));verify(orders).updateDelivery(7,33,null);
    }
    @ParameterizedTest @ValueSource(strings={"{\"deliveryStatus\":\"UNKNOWN\"}","{"})
    void given__invalid_enum_json_or_malformed_json__when__patching__then__original_generic_server_error_binding_family(String body) throws Exception {
        current(7L);mvc.perform(patch("/api/v1/admin/orders/33/delivery-status").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isInternalServerError()).andExpect(jsonPath("$.code").value("C004"));verifyNoInteractions(orders);
    }
    @ParameterizedTest @ValueSource(strings={"query","commit"})
    void given__database_or_commit_failure__when__patching__then__server_error_without_sql_or_order_details(String kind) throws Exception {
        current(7L);RuntimeException failure=kind.equals("query")?new PersistenceException("private query"):new org.springframework.transaction.TransactionSystemException("private commit");
        when(orders.updateDelivery(7,33,"SHIPPING")).thenThrow(failure);String body=mvc.perform(request("write")).andExpect(status().isInternalServerError()).andExpect(jsonPath("$.code").value("C004"))
                .andReturn().getResponse().getContentAsString();assertFalse(body.contains("private"));assertFalse(body.contains("address"));
    }
    @ParameterizedTest @ValueSource(strings={"/api/v1/admin/orders/33/status","/api/v1/orders/33/payment"})
    void given__unapproved_payment_state_path__when__requesting__then__no_handler(String path) throws Exception {
        current(7L);var call=path.endsWith("payment")?post(path):patch(path).contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"PAID\"}");mvc.perform(call).andExpect(status().isNotFound());verifyNoInteractions(orders);
    }
}
