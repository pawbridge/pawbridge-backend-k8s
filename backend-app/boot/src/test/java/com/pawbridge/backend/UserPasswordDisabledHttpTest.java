package com.pawbridge.backend;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.pawbridge.backend.boot.configuration.ReadSecurityConfiguration;
import com.pawbridge.backend.user.internal.password.application.ChangeUserPassword;
import com.pawbridge.backend.user.internal.password.web.UserPasswordController;
import com.pawbridge.backend.user.internal.password.web.UserPasswordExceptionHandler;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

// The controller is registered directly so only the default-off security route can block it.
@WebMvcTest(controllers = UserPasswordController.class, properties = "pawbridge.user-password.enabled=on",
        excludeAutoConfiguration = org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration.class)
@ContextConfiguration(classes = {UserPasswordController.class, UserPasswordExceptionHandler.class,
        ReadSecurityConfiguration.class, F3TestInputs.SecurityInputs.class})
class UserPasswordDisabledHttpTest {
    @Autowired MockMvc mvc;
    @MockitoBean ChangeUserPassword change;

    @Test
    void given__flag_not_exactly_true__when__changing_password_with_valid_token__then__route_is_denied_before_service() throws Exception {
        mvc.perform(put("/api/v1/users/me/password").header("Authorization", "Bearer " + F3TestInputs.token(7, "ROLE_USER"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"currentPassword\":\"Current1!\",\"newPassword\":\"NewPass1!\"}"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(change);
    }
}
