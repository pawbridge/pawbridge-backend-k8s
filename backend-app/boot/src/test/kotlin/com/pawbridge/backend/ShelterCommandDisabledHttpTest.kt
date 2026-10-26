package com.pawbridge.backend

import com.ninjasquad.springmockk.MockkBean
import com.pawbridge.backend.animal.internal.sheltercommand.application.ShelterCommands
import com.pawbridge.backend.animal.internal.sheltercommand.web.ShelterCommandController
import com.pawbridge.backend.boot.configuration.ReadSecurityConfiguration
import io.mockk.confirmVerified
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.test.context.ContextConfiguration
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

@WebMvcTest(excludeAutoConfiguration = [UserDetailsServiceAutoConfiguration::class])
@ContextConfiguration(
    classes = [ShelterCommandController::class, ReadSecurityConfiguration::class, F3TestInputs.SecurityInputs::class],
)
class ShelterCommandDisabledHttpTest {
    @Autowired
    lateinit var mvc: MockMvc

    @MockkBean
    lateinit var commands: ShelterCommands

    @ParameterizedTest
    @CsvSource("POST,/api/v1/shelters", "PUT,/api/v1/shelters/7", "DELETE,/api/v1/shelters/7")
    fun `명령 플래그가 없을 때__검증된 관리자가 명령 경로를 호출하면__비즈니스 호출 전에 거부한다`(
        method: String,
        path: String,
    ) {
        // given
        val request = command(method, path)

        // when
        val result = mvc.perform(request)

        // then
        result.andExpect(status().isForbidden())
        confirmVerified(commands)
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
    properties = ["pawbridge.shelter-commands.enabled=on"],
    excludeAutoConfiguration = [UserDetailsServiceAutoConfiguration::class],
)
@ContextConfiguration(
    classes = [ShelterCommandController::class, ReadSecurityConfiguration::class, F3TestInputs.SecurityInputs::class],
)
class ShelterCommandAliasDisabledHttpTest {
    @Autowired
    lateinit var mvc: MockMvc

    @MockkBean
    lateinit var commands: ShelterCommands

    @ParameterizedTest
    @CsvSource("POST,/api/v1/shelters", "PUT,/api/v1/shelters/7", "DELETE,/api/v1/shelters/7")
    fun `소유하지 않은 불리언 별칭이 주어졌을 때__검증된 관리자가 명령 경로를 호출하면__소유자 플래그를 닫아 둔다`(
        method: String,
        path: String,
    ) {
        // given
        val request = ShelterCommandDisabledHttpTest.command(method, path)

        // when
        val result = mvc.perform(request)

        // then
        result.andExpect(status().isForbidden())
        confirmVerified(commands)
    }
}
