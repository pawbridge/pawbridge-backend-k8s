package com.pawbridge.backend;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.pawbridge.backend.boot.configuration.ReadSecurityConfiguration;
import com.pawbridge.backend.store.internal.wishlist.application.WishlistCommands;
import com.pawbridge.backend.store.internal.wishlist.web.WishlistCommandController;
import com.pawbridge.backend.store.internal.wishlist.web.WishlistCommandExceptionHandler;
import java.util.Map;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(properties="pawbridge.store-wishlist.enabled=true",excludeAutoConfiguration=org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration.class)
@ContextConfiguration(classes={WishlistCommandController.class,WishlistCommandExceptionHandler.class,ReadSecurityConfiguration.class,F3TestInputs.SecurityInputs.class})
class StoreWishlistHttpTest {
    @Autowired MockMvc mvc;
    @MockitoBean WishlistCommands commands;

    @ParameterizedTest @CsvSource({"POST,ROLE_USER","POST,ROLE_ADMIN","DELETE,ROLE_USER","DELETE,ROLE_ADMIN"})
    void given__flag_on_and_verified_member__when__commanding_exact_route__then__existing_jwt_principal_overrides_forged_identity(String method,String role) throws Exception {
        when(commands.add(7,80L)).thenReturn(new WishlistCommands.Added(33L,7L,80L,"SKU",9L,"item",null,0L,Map.of(),null));
        var request=request(HttpMethod.valueOf(method),"/api/v1/wishlists").header("Authorization","Bearer "+F3TestInputs.token(7,role))
                .header("X-User-Id","99").header("X-User-Role","ROLE_ADMIN").param("userId","99").param("skuId","80")
                .contentType(MediaType.APPLICATION_JSON).content("{\"skuId\":80,\"userId\":99}");
        mvc.perform(request).andExpect(status().is(method.equals("POST")?201:204)).andExpect(header().doesNotExist("Set-Cookie"));
        if(method.equals("POST")) verify(commands).add(7,80L);else verify(commands).removeByMemberAndSku(7,80);
        verifyNoMoreInteractions(commands);
    }

    @ParameterizedTest @CsvSource({"POST,missing","POST,invalid","DELETE,missing","DELETE,invalid"})
    void given__flag_on_but_missing_or_invalid_jwt__when__forging_user_headers__then__existing_filter_returns_401_before_command(String method,String token) throws Exception {
        var request=request(HttpMethod.valueOf(method),"/api/v1/wishlists").param("skuId","80").header("X-User-Id","99").header("X-User-Role","ROLE_ADMIN")
                .contentType(MediaType.APPLICATION_JSON).content("{\"skuId\":80,\"userId\":99}");
        if(token.equals("invalid")) request.header("Authorization","Bearer invalid-token");
        mvc.perform(request).andExpect(status().isUnauthorized());verifyNoInteractions(commands);
    }

    @ParameterizedTest @CsvSource({"GET,/api/v1/wishlists","PUT,/api/v1/wishlists","PATCH,/api/v1/wishlists","DELETE,/api/v1/wishlists/33","POST,/api/v1/wishlists/33","DELETE,/api/v1/wishlists/33/extra","POST,/api/wishlists"})
    void given__verified_admin__when__attempting_unported_neighbors__then__deny_without_command(String method,String path) throws Exception {
        mvc.perform(request(HttpMethod.valueOf(method),path).header("Authorization","Bearer "+F3TestInputs.token(7,"ROLE_ADMIN")))
                .andExpect(status().isForbidden());verifyNoInteractions(commands);
    }
}

@WebMvcTest(excludeAutoConfiguration=org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration.class)
@ContextConfiguration(classes={WishlistCommandController.class,WishlistCommandExceptionHandler.class,ReadSecurityConfiguration.class,F3TestInputs.SecurityInputs.class})
class StoreWishlistDisabledHttpTest {
    @Autowired MockMvc mvc;
    @MockitoBean WishlistCommands commands;
    @ParameterizedTest @ValueSource(strings={"POST","DELETE"})
    void given__no_explicit_flag__when__verified_admin_commands_exact_routes__then__deny_before_application(String method) throws Exception {
        mvc.perform(request(HttpMethod.valueOf(method),"/api/v1/wishlists").param("skuId","80")
                .header("Authorization","Bearer "+F3TestInputs.token(7,"ROLE_ADMIN")).contentType(MediaType.APPLICATION_JSON).content("{\"skuId\":80}"))
                .andExpect(status().isForbidden());verifyNoInteractions(commands);
    }
}
