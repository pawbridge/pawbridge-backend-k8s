package com.pawbridge.backend

import com.ninjasquad.springmockk.MockkBean
import com.pawbridge.backend.animal.internal.apms.infrastructure.ApmsBatchRunner
import com.pawbridge.backend.animal.internal.apms.web.ApmsBatchController
import com.pawbridge.backend.boot.configuration.ReadSecurityConfiguration
import io.mockk.every
import io.mockk.verify
import org.junit.jupiter.api.Test
import org.springframework.batch.core.BatchStatus
import org.springframework.batch.core.JobExecution
import org.springframework.batch.core.JobInstance
import org.springframework.batch.core.JobParameters
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.test.context.ContextConfiguration
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

@WebMvcTest(
    properties = ["pawbridge.apms-manual.enabled=true"],
    excludeAutoConfiguration = [UserDetailsServiceAutoConfiguration::class],
)
@ContextConfiguration(
    classes = [
        ApmsManualSyncHttpTest.ControllerInputs::class,
        ReadSecurityConfiguration::class,
        F3TestInputs.SecurityInputs::class,
    ],
)
class ApmsManualSyncHttpTest {
    @Autowired
    lateinit var mvc: MockMvc

    @MockkBean
    lateinit var runner: ApmsBatchRunner

    @Test
    fun `관리자 토큰일 때__실행기가 완료되거나 이미 실행 중이면__원래 상태와 본문을 돌려준다`() {
        // given
        val execution = JobExecution(JobInstance(1L, "apmsAnimalSyncJob"), 9L, JobParameters())
        execution.status = BatchStatus.COMPLETED
        val admin = "Bearer " + F3TestInputs.token(7, "ROLE_ADMIN")

        every { runner.run() } returns execution andThenThrows ApmsBatchRunner.AlreadyRunningException()

        // when
        val completed = mvc.perform(post(PATH).header("Authorization", admin))
        val busy = mvc.perform(post(PATH).header("Authorization", admin))

        // then
        completed.andExpect(status().isOk())
            .andExpect(jsonPath("$.jobExecutionId").value(9))
            .andExpect(jsonPath("$.status").value("COMPLETED"))
            .andExpect(jsonPath("$.skipCount").value(0))
            .andExpect(jsonPath("$.searchSyncStatus").value("NOT_STARTED"))
            .andExpect(header().doesNotExist("Set-Cookie"))
        busy.andExpect(status().isConflict())
            .andExpect(jsonPath("$.status").value("ALREADY_RUNNING"))
        verify(exactly = 2) { runner.run() }
    }

    @Configuration(proxyBeanMethods = false)
    class ControllerInputs {
        @Bean
        fun apmsBatchController(runner: ApmsBatchRunner): ApmsBatchController = ApmsBatchController(runner)
    }

    companion object {
        private const val PATH = "/api/v1/batch/apms/sync"
    }
}
