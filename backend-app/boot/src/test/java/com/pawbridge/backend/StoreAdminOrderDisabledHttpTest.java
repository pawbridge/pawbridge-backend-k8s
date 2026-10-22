package com.pawbridge.backend;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
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
import com.pawbridge.backend.store.internal.orderadministration.application.AdminOrders;
import com.pawbridge.backend.store.internal.orderadministration.web.AdminOrderController;

@WebMvcTest(excludeAutoConfiguration=org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration.class)
@ContextConfiguration(classes={AdminOrderController.class,ReadSecurityConfiguration.class,F3TestInputs.SecurityInputs.class})
class StoreAdminOrderDisabledHttpTest {
    @Autowired MockMvc mvc; @MockitoBean AdminOrders orders;
    @ParameterizedTest @CsvSource({"GET,/api/v1/admin/orders","GET,/api/v1/admin/orders/7","PATCH,/api/v1/admin/orders/7/delivery-status","PATCH,/api/v1/admin/orders/7/status"})
    void given__disabled_administration__when__signed_administrator_uses_a_route__then__deny_before_business(String method,String path) throws Exception {
        mvc.perform(command(method,path)).andExpect(status().isForbidden());verifyNoInteractions(orders);
    }
    static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder command(String method,String path) {
        return request(HttpMethod.valueOf(method),path).header("Authorization","Bearer "+F3TestInputs.token(7,"ROLE_ADMIN"))
                .header("X-User-Id","99").contentType(MediaType.APPLICATION_JSON).content("{}");
    }
}
@WebMvcTest(properties="pawbridge.store-order-administration.enabled=on",excludeAutoConfiguration=org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration.class)
@ContextConfiguration(classes={AdminOrderController.class,ReadSecurityConfiguration.class,F3TestInputs.SecurityInputs.class})
class StoreAdminOrderAliasDisabledHttpTest {
    @Autowired MockMvc mvc; @MockitoBean AdminOrders orders;
    @ParameterizedTest @CsvSource({"GET,/api/v1/admin/orders","GET,/api/v1/admin/orders/7","PATCH,/api/v1/admin/orders/7/delivery-status","PATCH,/api/v1/admin/orders/7/status"})
    void given__boolean_alias__when__signed_administrator_uses_a_route__then__keep_owner_flag_closed(String method,String path) throws Exception {
        mvc.perform(StoreAdminOrderDisabledHttpTest.command(method,path)).andExpect(status().isForbidden());verifyNoInteractions(orders);
    }
}
@WebMvcTest(properties="pawbridge.store-order-administration.enabled=true",excludeAutoConfiguration=org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration.class)
@ContextConfiguration(classes={AdminOrderController.class,ReadSecurityConfiguration.class,F3TestInputs.SecurityInputs.class})
class StoreAdminOrderNeighborDisabledHttpTest {
    @Autowired MockMvc mvc; @MockitoBean AdminOrders orders;
    @ParameterizedTest @CsvSource({"PUT,/api/v1/orders/7/status","PUT,/api/v1/orders/7/cancel","GET,/api/v1/orders/uuid/example","POST,/api/v1/payments/confirm","GET,/api/v1/orders/7","PUT,/api/v1/admin/orders/7/status","POST,/api/v1/admin/orders/7/status","PATCH,/api/v1/admin/orders/7/status/","PATCH,/api/v1/admin/orders/7/status/extra"})
    void given__only_administration_enabled__when__an_unported_order_operation_is_requested__then__deny_before_business(String method,String path) throws Exception {
        mvc.perform(StoreAdminOrderDisabledHttpTest.command(method,path)).andExpect(status().isForbidden());verifyNoInteractions(orders);
    }
}
