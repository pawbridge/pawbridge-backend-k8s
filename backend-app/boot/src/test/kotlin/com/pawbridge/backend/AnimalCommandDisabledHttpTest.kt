package com.pawbridge.backend

import com.pawbridge.backend.animal.internal.command.infrastructure.service.AnimalCommandService
import com.pawbridge.backend.animal.internal.command.web.AnimalCommandController
import com.pawbridge.backend.boot.configuration.ReadSecurityConfiguration
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.mockito.Mockito.verifyNoInteractions
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.test.context.ContextConfiguration
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

@WebMvcTest(excludeAutoConfiguration = [UserDetailsServiceAutoConfiguration::class])
@ContextConfiguration(
    classes = [AnimalCommandController::class, ReadSecurityConfiguration::class, F3TestInputs.SecurityInputs::class],
)
class AnimalCommandDisabledHttpTest {
    @Autowired
    lateinit var mvc: MockMvc

    @MockitoBean
    lateinit var commands: AnimalCommandService

    @ParameterizedTest
    @CsvSource(
        "POST,/api/v1/animals",
        "PATCH,/api/v1/animals/7/status",
        "PATCH,/api/v1/animals/7/description",
        "DELETE,/api/v1/animals/7",
    )
    fun given__no_command_flag__when__verified_admin_uses_a_command_route__then__deny_before_business(
        method: String,
        path: String,
    ) {
        mvc.perform(command(method, path)).andExpect(status().isForbidden())
        verifyNoInteractions(commands)
    }

    companion object {
        fun command(method: String, path: String): MockHttpServletRequestBuilder {
            return request(HttpMethod.valueOf(method), path)
                .header("Authorization", "Bearer " + F3TestInputs.token(7, "ROLE_ADMIN"))
                .header("X-User-Id", "99")
                .header("X-User-Role", "ROLE_ADMIN")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}")
        }
    }
}

@WebMvcTest(
    properties = ["pawbridge.animal-commands.enabled=on"],
    excludeAutoConfiguration = [UserDetailsServiceAutoConfiguration::class],
)
@ContextConfiguration(
    classes = [AnimalCommandController::class, ReadSecurityConfiguration::class, F3TestInputs.SecurityInputs::class],
)
class AnimalCommandAliasDisabledHttpTest {
    @Autowired
    lateinit var mvc: MockMvc

    @MockitoBean
    lateinit var commands: AnimalCommandService

    @ParameterizedTest
    @CsvSource(
        "POST,/api/v1/animals",
        "PATCH,/api/v1/animals/7/status",
        "PATCH,/api/v1/animals/7/description",
        "DELETE,/api/v1/animals/7",
    )
    fun given__unowned_boolean_alias__when__verified_admin_uses_a_command_route__then__keep_the_owner_flag_closed(
        method: String,
        path: String,
    ) {
        mvc.perform(AnimalCommandDisabledHttpTest.command(method, path)).andExpect(status().isForbidden())
        verifyNoInteractions(commands)
    }
}
