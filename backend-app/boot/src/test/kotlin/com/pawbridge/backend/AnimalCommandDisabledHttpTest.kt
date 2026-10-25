package com.pawbridge.backend

import com.ninjasquad.springmockk.MockkBean
import com.pawbridge.backend.animal.internal.command.infrastructure.service.AnimalCommandService
import com.pawbridge.backend.animal.internal.command.web.AnimalCommandController
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
    classes = [AnimalCommandController::class, ReadSecurityConfiguration::class, F3TestInputs.SecurityInputs::class],
)
class AnimalCommandDisabledHttpTest {
    @Autowired
    lateinit var mvc: MockMvc

    @MockkBean
    lateinit var commands: AnimalCommandService

    @ParameterizedTest
    @CsvSource(
        "POST,/api/v1/animals",
        "PATCH,/api/v1/animals/7/status",
        "PATCH,/api/v1/animals/7/description",
        "DELETE,/api/v1/animals/7",
    )
    fun `명령 플래그 없음__검증된 관리자가 명령 경로를 쓰면__업무 처리 전에 거부한다`(
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
    properties = ["pawbridge.animal-commands.enabled=on"],
    excludeAutoConfiguration = [UserDetailsServiceAutoConfiguration::class],
)
@ContextConfiguration(
    classes = [AnimalCommandController::class, ReadSecurityConfiguration::class, F3TestInputs.SecurityInputs::class],
)
class AnimalCommandAliasDisabledHttpTest {
    @Autowired
    lateinit var mvc: MockMvc

    @MockkBean
    lateinit var commands: AnimalCommandService

    @ParameterizedTest
    @CsvSource(
        "POST,/api/v1/animals",
        "PATCH,/api/v1/animals/7/status",
        "PATCH,/api/v1/animals/7/description",
        "DELETE,/api/v1/animals/7",
    )
    fun `소유되지 않은 불리언 별칭__검증된 관리자가 명령 경로를 쓰면__소유자 플래그를 닫은 채로 유지한다`(
        method: String,
        path: String,
    ) {
        // given
        val request = AnimalCommandDisabledHttpTest.command(method, path)

        // when
        val result = mvc.perform(request)

        // then
        result.andExpect(status().isForbidden())
        confirmVerified(commands)
    }
}
