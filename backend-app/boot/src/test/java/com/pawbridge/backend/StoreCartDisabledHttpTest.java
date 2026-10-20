package com.pawbridge.backend;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.pawbridge.backend.store.internal.cart.application.Carts;
import com.pawbridge.backend.store.internal.cart.web.CartController;
import com.pawbridge.backend.boot.configuration.ReadSecurityConfiguration;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(excludeAutoConfiguration=org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration.class)
@ContextConfiguration(classes={CartController.class,ReadSecurityConfiguration.class,F3TestInputs.SecurityInputs.class})
class StoreCartDisabledHttpTest {
    @Autowired MockMvc mvc;
    @MockitoBean Carts carts;

    @ParameterizedTest @CsvSource({"GET,/api/v1/carts","POST,/api/v1/carts/items","PATCH,/api/v1/carts/items/7","DELETE,/api/v1/carts/items/7","DELETE,/api/v1/carts","POST,/api/v1/orders","GET,/api/v1/orders/7"})
    void given__no_cart_flag__when__verified_member_uses_a_cart_route__then__deny_before_business(String method,String path) throws Exception {
        mvc.perform(command(method,path)).andExpect(status().isForbidden());
        verifyNoInteractions(carts);
    }
    static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder command(String method,String path) {
        return request(HttpMethod.valueOf(method),path)
                .header("Authorization","Bearer "+F3TestInputs.token(7,"ROLE_USER"))
                .header("X-User-Id","99").contentType(MediaType.APPLICATION_JSON).content("{}");
    }
}

@WebMvcTest(properties="pawbridge.store-carts.enabled=on",
        excludeAutoConfiguration=org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration.class)
@ContextConfiguration(classes={CartController.class,ReadSecurityConfiguration.class,F3TestInputs.SecurityInputs.class})
class StoreCartAliasDisabledHttpTest {
    @Autowired MockMvc mvc;
    @MockitoBean Carts carts;
    @ParameterizedTest @CsvSource({"GET,/api/v1/carts","POST,/api/v1/carts/items","PATCH,/api/v1/carts/items/7","DELETE,/api/v1/carts/items/7","DELETE,/api/v1/carts","POST,/api/v1/orders","GET,/api/v1/orders/7"})
    void given__unowned_boolean_alias__when__verified_member_uses_a_route__then__keep_the_owner_flag_closed(String method,String path) throws Exception {
        mvc.perform(StoreCartDisabledHttpTest.command(method,path)).andExpect(status().isForbidden());
        verifyNoInteractions(carts);
    }
}

@WebMvcTest(properties="pawbridge.store-carts.enabled=true",
        excludeAutoConfiguration=org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration.class)
@ContextConfiguration(classes={CartController.class,ReadSecurityConfiguration.class,F3TestInputs.SecurityInputs.class})
class StoreCartNeighborDisabledHttpTest {
    @Autowired MockMvc mvc;
    @MockitoBean Carts carts;
    @ParameterizedTest @CsvSource({"POST,/api/v1/carts/reset","GET,/api/v1/carts/99","PUT,/api/v1/carts/items/7","POST,/api/v1/orders/direct","POST,/api/v1/payments/confirm"})
    void given__enabled_cart_management_only__when__verified_member_uses_an_unported_route__then__keep_neighbor_routes_closed(String method,String path) throws Exception {
        mvc.perform(StoreCartDisabledHttpTest.command(method,path)).andExpect(status().isForbidden());
        verifyNoInteractions(carts);
    }
}
