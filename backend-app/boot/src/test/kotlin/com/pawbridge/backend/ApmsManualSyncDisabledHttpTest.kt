package com.pawbridge.backend

import com.ninjasquad.springmockk.MockkBean
import com.pawbridge.backend.animal.internal.apms.infrastructure.ApmsBatchRunner
import com.pawbridge.backend.boot.configuration.ReadSecurityConfiguration
import io.mockk.confirmVerified
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.test.context.ContextConfiguration
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

@WebMvcTest(excludeAutoConfiguration = [UserDetailsServiceAutoConfiguration::class])
@ContextConfiguration(
    classes = [
        ApmsManualSyncHttpTest.ControllerInputs::class,
        ReadSecurityConfiguration::class,
        F3TestInputs.SecurityInputs::class,
    ],
)
class ApmsManualSyncDisabledHttpTest {
    @Autowired
    lateinit var mvc: MockMvc

    @MockkBean
    lateinit var runner: ApmsBatchRunner

    @Test
    fun `플래그가 기본값일 때__익명이나 관리자가 수동 동기화를 POST하면__실행기 없이 기본 거부를 유지한다`() {
        // when
        for (token in listOf("", "Bearer " + F3TestInputs.token(7, "ROLE_ADMIN"))) {
            var request = post("/api/v1/batch/apms/sync")
            if (token.isNotEmpty()) {
                request = request.header("Authorization", token)
            }
            mvc.perform(request).andExpect(status().isForbidden())
        }

        // then
        confirmVerified(runner)
    }
}
