package com.pawbridge.backend;

import com.pawbridge.backend.animal.internal.apms.infrastructure.ApmsBatchRunner;
import com.pawbridge.backend.animal.internal.apms.web.ApmsBatchController;
import com.pawbridge.backend.boot.configuration.ReadSecurityConfiguration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobInstance;
import org.springframework.batch.core.JobParameters;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(properties = "pawbridge.apms-manual.enabled=true",
        excludeAutoConfiguration = org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration.class)
@ContextConfiguration(classes = {ApmsManualSyncHttpTest.ControllerInputs.class, ReadSecurityConfiguration.class, F3TestInputs.SecurityInputs.class})
class ApmsManualSyncHttpTest {
    private static final String PATH = "/api/v1/batch/apms/sync";
    @Autowired MockMvc mvc;
    @MockitoBean ApmsBatchRunner runner;

    @Test
    void given__enabled_manual_sync__when__missing_invalid_user_or_shelter_tokens__then__reject_before_runner() throws Exception {
        mvc.perform(post(PATH)).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value(401));
        mvc.perform(post(PATH).header("Authorization", "Bearer invalid")).andExpect(status().isUnauthorized());
        for (String role : List.of("ROLE_USER", "ROLE_SHELTER")) {
            mvc.perform(post(PATH).header("Authorization", "Bearer " + F3TestInputs.token(7, role)))
                    .andExpect(status().isForbidden()).andExpect(jsonPath("$.message").value("관리자 권한이 필요합니다."));
        }
        verifyNoInteractions(runner);
    }

    @Test
    void given__admin_token__when__runner_completes_or_is_busy__then__original_status_and_body() throws Exception {
        var execution = new JobExecution(new JobInstance(1L, "apmsAnimalSyncJob"), 9L, new JobParameters());
        execution.setStatus(BatchStatus.COMPLETED);
        when(runner.run()).thenReturn(execution).thenThrow(new ApmsBatchRunner.AlreadyRunningException());
        String admin = "Bearer " + F3TestInputs.token(7, "ROLE_ADMIN");

        mvc.perform(post(PATH).header("Authorization", admin))
                .andExpect(status().isOk()).andExpect(jsonPath("$.jobExecutionId").value(9))
                .andExpect(jsonPath("$.status").value("COMPLETED")).andExpect(jsonPath("$.skipCount").value(0))
                .andExpect(jsonPath("$.searchSyncStatus").value("NOT_STARTED")).andExpect(header().doesNotExist("Set-Cookie"));
        mvc.perform(post(PATH).header("Authorization", admin))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.status").value("ALREADY_RUNNING"));
        verify(runner, times(2)).run();
    }

    @Test
    void given__exact_post_only__when__admin_requests_neighbors_aliases_or_other_methods__then__deny() throws Exception {
        String admin = "Bearer " + F3TestInputs.token(7, "ROLE_ADMIN");
        for (String path : List.of("/api/batch/apms/sync", "/api/v1/v1/batch/apms/sync", PATH + "/extra", "/api/v1/batch/apms",
                "/api/v1/batch/apms/sync/")) {
            mvc.perform(post(path).header("Authorization", admin)).andExpect(status().isForbidden());
        }
        for (HttpMethod method : List.of(HttpMethod.GET, HttpMethod.PUT, HttpMethod.PATCH, HttpMethod.DELETE)) {
            mvc.perform(request(method, PATH).header("Authorization", admin)).andExpect(status().isForbidden());
        }
        verifyNoInteractions(runner);
    }

    @Configuration(proxyBeanMethods = false)
    static class ControllerInputs {
        @Bean ApmsBatchController apmsBatchController(ApmsBatchRunner runner) { return new ApmsBatchController(runner); }
    }
}
