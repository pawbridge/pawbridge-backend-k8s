package com.pawbridge.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.pawbridge.backend.boot.configuration.ReadSecurityConfiguration;
import com.pawbridge.backend.user.internal.storemypage.application.StoreMyPage;
import com.pawbridge.backend.user.internal.storemypage.web.StoreMyPageController;
import com.pawbridge.backend.user.internal.storemypage.web.StoreMyPageExceptionHandler;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(properties = "pawbridge.user-store-mypage.enabled=true",
        excludeAutoConfiguration = org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration.class)
@ContextConfiguration(classes = {StoreMyPageController.class, StoreMyPageExceptionHandler.class,
        ReadSecurityConfiguration.class, F3TestInputs.SecurityInputs.class})
class UserStoreMyPageHttpTest {
    private static final String ROOT = "/api/v1/users/me/";
    @Autowired MockMvc mvc;
    @MockitoBean StoreMyPage myPage;

    @Test
    void given__verified_member_and_forged_identity__when__reading_all_three_routes__then__use_only_jwt_user_and_preserve_empty_cart_204() throws Exception {
        when(myPage.wishlists(eq(7L), any())).thenReturn(new StoreMyPage.Page<>(List.of(), 0, 20, 0, List.of()));
        when(myPage.orders(eq(7L), any())).thenReturn(new StoreMyPage.Page<>(List.of(), 0, 20, 0, List.of()));
        String token = "Bearer " + F3TestInputs.token(7, "ROLE_USER");
        for (String path : List.of("wishlists", "orders")) {
            mvc.perform(get(ROOT + path).header("Authorization", token).header("X-User-Id", "999"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.data.totalElements").value(0))
                    .andExpect(header().doesNotExist("Set-Cookie"));
        }
        mvc.perform(get(ROOT + "cart").header("Authorization", token).header("X-User-Id", "999"))
                .andExpect(status().isNoContent()).andExpect(content().string(""));
        var defaults = new StoreMyPage.Request(0, 20, "createdAt,desc");
        verify(myPage).wishlists(7, defaults);
        verify(myPage).orders(7, defaults);
        verify(myPage).cart(7);
        verifyNoMoreInteractions(myPage);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @ParameterizedTest @ValueSource(strings = {"wishlists", "orders", "cart"})
    void given__missing_jwt_and_forged_headers__when__reading_own_data__then__reject_before_service(String path) throws Exception {
        mvc.perform(get(ROOT + path).header("X-User-Id", "7").header("X-User-Role", "ROLE_ADMIN"))
                .andExpect(status().isUnauthorized()).andExpect(header().doesNotExist("Set-Cookie"));
        verifyNoInteractions(myPage);
    }

    @Test
    void given__an_authenticated_member__when__requesting_an_unknown_neighbor__then__deny_without_store_call() throws Exception {
        mvc.perform(get(ROOT + "cart/items").header("Authorization", "Bearer " + F3TestInputs.token(7, "ROLE_USER")))
                .andExpect(status().isForbidden());
        verifyNoInteractions(myPage);
    }
}

@WebMvcTest(properties = "pawbridge.user-store-mypage.enabled=false",
        excludeAutoConfiguration = org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration.class)
@ContextConfiguration(classes = {StoreMyPageController.class, StoreMyPageExceptionHandler.class,
        ReadSecurityConfiguration.class, F3TestInputs.SecurityInputs.class})
class UserStoreMyPageDisabledHttpTest {
    @Autowired MockMvc mvc;
    @MockitoBean StoreMyPage myPage;

    @ParameterizedTest @ValueSource(strings = {"wishlists", "orders", "cart"})
    void given__feature_disabled__when__requesting_each_own_route__then__deny_even_with_valid_jwt(String path) throws Exception {
        mvc.perform(get("/api/v1/users/me/" + path).header("Authorization", "Bearer " + F3TestInputs.token(7, "ROLE_USER")))
                .andExpect(status().isForbidden());
        verifyNoInteractions(myPage);
    }
}
