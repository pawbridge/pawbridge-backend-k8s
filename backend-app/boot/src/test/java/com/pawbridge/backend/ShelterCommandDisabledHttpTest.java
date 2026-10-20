package com.pawbridge.backend;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.pawbridge.backend.animal.internal.sheltercommand.application.ShelterCommands;
import com.pawbridge.backend.animal.internal.sheltercommand.web.ShelterCommandController;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@WebMvcTest(excludeAutoConfiguration=org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration.class)
@ContextConfiguration(classes={ShelterCommandController.class,ReadSecurityConfiguration.class,F3TestInputs.SecurityInputs.class})
class ShelterCommandDisabledHttpTest {
    @Autowired MockMvc mvc;
    @MockitoBean ShelterCommands commands;

    @ParameterizedTest @CsvSource({"POST,/api/v1/shelters","PUT,/api/v1/shelters/7","DELETE,/api/v1/shelters/7"})
    void given__no_command_flag__when__verified_admin_uses_a_command_route__then__deny_before_business(String method,String path) throws Exception {
        mvc.perform(command(method,path)).andExpect(status().isForbidden());
        verifyNoInteractions(commands);
    }

    static MockHttpServletRequestBuilder command(String method,String path) {
        return request(HttpMethod.valueOf(method),path)
                .header("Authorization","Bearer "+F3TestInputs.token(7,"ROLE_ADMIN"))
                .header("X-User-Id","99").header("X-User-Role","ROLE_ADMIN")
                .contentType(MediaType.APPLICATION_JSON).content("{}");
    }
}

@WebMvcTest(properties="pawbridge.shelter-commands.enabled=on",
        excludeAutoConfiguration=org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration.class)
@ContextConfiguration(classes={ShelterCommandController.class,ReadSecurityConfiguration.class,F3TestInputs.SecurityInputs.class})
class ShelterCommandAliasDisabledHttpTest {
    @Autowired MockMvc mvc;
    @MockitoBean ShelterCommands commands;

    @ParameterizedTest @CsvSource({"POST,/api/v1/shelters","PUT,/api/v1/shelters/7","DELETE,/api/v1/shelters/7"})
    void given__unowned_boolean_alias__when__verified_admin_uses_a_command_route__then__keep_the_owner_flag_closed(String method,String path) throws Exception {
        mvc.perform(ShelterCommandDisabledHttpTest.command(method,path)).andExpect(status().isForbidden());
        verifyNoInteractions(commands);
    }
}
