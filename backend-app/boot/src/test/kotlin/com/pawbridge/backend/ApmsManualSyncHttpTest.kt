package com.pawbridge.backend

import com.pawbridge.backend.animal.internal.apms.infrastructure.ApmsBatchRunner
import com.pawbridge.backend.animal.internal.apms.web.ApmsBatchController
import com.pawbridge.backend.boot.configuration.ReadSecurityConfiguration
import org.junit.jupiter.api.Test
import org.mockito.Mockito.`when` as whenever
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.springframework.batch.core.BatchStatus
import org.springframework.batch.core.JobExecution
import org.springframework.batch.core.JobInstance
import org.springframework.batch.core.JobParameters
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpMethod
import org.springframework.test.context.ContextConfiguration
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request
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

    @MockitoBean
    lateinit var runner: ApmsBatchRunner

    @Test
    fun given__enabled_manual_sync__when__missing_invalid_user_or_shelter_tokens__then__reject_before_runner() {
        mvc.perform(post(PATH)).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value(401))
        mvc.perform(post(PATH).header("Authorization", "Bearer invalid")).andExpect(status().isUnauthorized())
        for (role in listOf("ROLE_USER", "ROLE_SHELTER")) {
            mvc.perform(post(PATH).header("Authorization", "Bearer " + F3TestInputs.token(7, role)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("관리자 권한이 필요합니다."))
        }
        verifyNoInteractions(runner)
    }

    @Test
    fun given__admin_token__when__runner_completes_or_is_busy__then__original_status_and_body() {
        val execution = JobExecution(JobInstance(1L, "apmsAnimalSyncJob"), 9L, JobParameters())
        execution.status = BatchStatus.COMPLETED
        whenever(runner.run()).thenReturn(execution).thenThrow(ApmsBatchRunner.AlreadyRunningException())
        val admin = "Bearer " + F3TestInputs.token(7, "ROLE_ADMIN")

        mvc.perform(post(PATH).header("Authorization", admin))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.jobExecutionId").value(9))
            .andExpect(jsonPath("$.status").value("COMPLETED"))
            .andExpect(jsonPath("$.skipCount").value(0))
            .andExpect(jsonPath("$.searchSyncStatus").value("NOT_STARTED"))
            .andExpect(header().doesNotExist("Set-Cookie"))
        mvc.perform(post(PATH).header("Authorization", admin))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.status").value("ALREADY_RUNNING"))
        verify(runner, times(2)).run()
    }

    @Test
    fun given__exact_post_only__when__admin_requests_neighbors_aliases_or_other_methods__then__deny() {
        val admin = "Bearer " + F3TestInputs.token(7, "ROLE_ADMIN")
        val paths = listOf(
            "/api/batch/apms/sync",
            "/api/v1/v1/batch/apms/sync",
            "$PATH/extra",
            "/api/v1/batch/apms",
            "/api/v1/batch/apms/sync/",
        )
        for (path in paths) {
            mvc.perform(post(path).header("Authorization", admin)).andExpect(status().isForbidden())
        }
        for (method in listOf(HttpMethod.GET, HttpMethod.PUT, HttpMethod.PATCH, HttpMethod.DELETE)) {
            mvc.perform(request(method, PATH).header("Authorization", admin)).andExpect(status().isForbidden())
        }
        verifyNoInteractions(runner)
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
