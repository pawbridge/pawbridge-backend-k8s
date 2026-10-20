package com.pawbridge.backend.store.internal.cart.web;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pawbridge.backend.store.internal.cart.application.Carts;
import com.pawbridge.backend.store.internal.cart.application.CartActorRejectedException;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class CartControllerTest {
    final Carts carts=mock(Carts.class);
    final ObjectMapper json=new ObjectMapper().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    MockMvc mvc;
    @BeforeEach void setup() {
        SecurityContextHolder.clearContext();
        mvc=MockMvcBuilders.standaloneSetup(new CartController(carts)).setControllerAdvice(new CartExceptionHandler())
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(json)).build();
    }
    @AfterEach void clear(){SecurityContextHolder.clearContext();}
    void current(Object user){SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(user,"unused",List.of()));}
    static MockHttpServletRequestBuilder request(String operation) {
        return switch(operation){case "get"->get("/api/v1/carts");case "add"->post("/api/v1/carts/items").contentType(MediaType.APPLICATION_JSON).content("{\"skuId\":80,\"quantity\":2,\"userId\":99}");
            case "update"->patch("/api/v1/carts/items/80").param("quantity","-1");case "remove"->delete("/api/v1/carts/items/80");default->delete("/api/v1/carts");};
    }
    @Test void given__verified_member__when__reading__then__raw_seven_field_array_with_null_id_and_exact_long_values() throws Exception {
        current(7L);when(carts.get(7)).thenReturn(List.of(new Carts.Item(null,80L,"LIVE-80","live product",9007199254740993L,2,18014398509481986L)));
        var body=mvc.perform(request("get").header("X-User-Id","99").param("userId","99")).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        var node=json.readTree(body);assertTrue(node.isArray());assertEquals(1,node.size());var item=node.get(0);assertEquals(7,item.size());
        assertTrue(item.get("id").isNull());assertEquals(80L,item.get("skuId").longValue());assertEquals("LIVE-80",item.get("skuCode").asText());
        assertEquals("live product",item.get("productName").asText());assertEquals(9007199254740993L,item.get("price").longValue());
        assertEquals(2,item.get("quantity").intValue());assertEquals(18014398509481986L,item.get("totalPrice").longValue());verify(carts).get(7);verifyNoMoreInteractions(carts);
    }
    @Test void given__empty_cart__when__reading__then__empty_array() throws Exception {
        current(7L);when(carts.get(7)).thenReturn(List.of());mvc.perform(request("get")).andExpect(status().isOk()).andExpect(content().json("[]"));
    }
    @ParameterizedTest @ValueSource(strings={"add","update","remove","clear"})
    void given__verified_member_with_forged_user_sources__when__mutating__then__only_principal_and_original_200_empty(String operation) throws Exception {
        current(7L);mvc.perform(request(operation).header("X-User-Id","99").param("userId","99")).andExpect(status().isOk()).andExpect(content().string(""));
        switch(operation){case "add"->verify(carts).add(7,80L,2);case "update"->verify(carts).update(7,80,-1);case "remove"->verify(carts).remove(7,80);default->verify(carts).clear(7);}
        verifyNoMoreInteractions(carts);
    }
    static Stream<Arguments> invalidPrincipals(){return Stream.of("missing","text").flatMap(kind->Stream.of("get","add","update","remove","clear").map(op->Arguments.of(kind,op)));}
    @ParameterizedTest @MethodSource("invalidPrincipals")
    void given__missing_or_non_long_principal__when__accessing_any_cart_endpoint__then__unauthorized_before_application(String kind,String operation) throws Exception {
        if(kind.equals("text"))current("99");mvc.perform(request(operation).header("X-User-Id","99").param("userId","99"))
                .andExpect(status().isUnauthorized()).andExpect(content().string(""));verifyNoInteractions(carts);
    }
    @ParameterizedTest @ValueSource(strings={"quantity","skuId"})
    void given__invalid_integer_argument__when__patching__then__original_type_error_without_application(String field) throws Exception {
        current(7L);var call=field.equals("skuId")?patch("/api/v1/carts/items/bad").param("quantity","1"):patch("/api/v1/carts/items/80").param("quantity","2147483648");
        mvc.perform(call).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("C005"))
                .andExpect(jsonPath("$.errors[0].field").value(field)).andExpect(jsonPath("$.errors[0].reason").value("typeMismatch"));verifyNoInteractions(carts);
    }
    @Test void given__missing_quantity__when__patching__then__bad_input_before_application() throws Exception {
        current(7L);mvc.perform(patch("/api/v1/carts/items/80")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("C001"));verifyNoInteractions(carts);
    }
    static Stream<Arguments> failures(){return Stream.of(Arguments.of(new CartActorRejectedException(),403,"C006"),
            Arguments.of(new IllegalArgumentException("private overflow"),400,"C001"),
            Arguments.of(new org.springframework.dao.DataAccessResourceFailureException("private SQL"),500,"C004"),
            Arguments.of(new org.springframework.transaction.TransactionSystemException("private commit"),500,"C004"));}
    @ParameterizedTest @MethodSource("failures")
    void given__actor_or_validation_or_persistence_failure__when__adding__then__sanitized_error_without_success(RuntimeException failure,int statusCode,String code) throws Exception {
        current(7L);doThrow(failure).when(carts).add(7,80L,2);
        String body=mvc.perform(request("add")).andExpect(status().is(statusCode)).andExpect(jsonPath("$.code").value(code))
                .andExpect(jsonPath("$.errors").isEmpty()).andReturn().getResponse().getContentAsString();assertFalse(body.contains("private"));
    }
    @Test void given__reset_path__when__deleting__then__no_customer_reset_handler() throws Exception {
        current(7L);mvc.perform(delete("/api/v1/carts/reset")).andExpect(status().isNotFound());verifyNoInteractions(carts);
    }
}
