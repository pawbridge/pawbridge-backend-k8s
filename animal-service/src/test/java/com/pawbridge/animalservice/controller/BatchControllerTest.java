package com.pawbridge.animalservice.controller;

import com.pawbridge.animalservice.batch.ApmsBatchRunner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.JobExecution;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@ExtendWith(MockitoExtension.class)
class BatchControllerTest {
    @Mock private ApmsBatchRunner runner;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new BatchController(runner)).build();
    }

    @Test
    void givenCompletedWithoutSkips__whenSync__thenReturnSuccess() throws Exception {
        when(runner.run()).thenReturn(execution(BatchStatus.COMPLETED, 0));
        mvc.perform(post("/api/v1/batch/apms/sync")).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.skipCount").value(0));
    }

    @Test
    void givenFailedJob__whenSync__thenReturnServerError() throws Exception {
        when(runner.run()).thenReturn(execution(BatchStatus.FAILED, 0));
        mvc.perform(post("/api/v1/batch/apms/sync")).andExpect(status().isInternalServerError());
    }

    @Test
    void givenCompletedWithSkips__whenSync__thenRejectPartialSuccess() throws Exception {
        when(runner.run()).thenReturn(execution(BatchStatus.COMPLETED, 1));
        mvc.perform(post("/api/v1/batch/apms/sync")).andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.skipCount").value(1));
    }

    @Test
    void givenRunningJob__whenSync__thenDoNotReportCompletion() throws Exception {
        when(runner.run()).thenReturn(execution(BatchStatus.STARTED, 0));
        mvc.perform(post("/api/v1/batch/apms/sync")).andExpect(status().isServiceUnavailable());
    }

    @Test
    void givenConcurrentRequest__whenSync__thenReturnConflict() throws Exception {
        when(runner.run()).thenThrow(new ApmsBatchRunner.AlreadyRunningException());
        mvc.perform(post("/api/v1/batch/apms/sync")).andExpect(status().isConflict());
    }

    @Test
    void givenUncertainGuard__whenSync__thenReturnServiceUnavailable() throws Exception {
        when(runner.run()).thenThrow(new ApmsBatchRunner.UnavailableException());
        mvc.perform(post("/api/v1/batch/apms/sync")).andExpect(status().isServiceUnavailable());
    }

    @Test
    void givenLauncherException__whenSync__thenHideSensitiveDetails() throws Exception {
        when(runner.run()).thenThrow(new IllegalStateException("private-service-key-placeholder"));
        mvc.perform(post("/api/v1/batch/apms/sync")).andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error").value("APMS batch execution failed"));
    }

    private JobExecution execution(BatchStatus status, long skips) {
        var execution = new JobExecution(42L);
        execution.setStatus(status);
        execution.createStepExecution("apmsAnimalSyncStep").setWriteSkipCount(skips);
        return execution;
    }
}
