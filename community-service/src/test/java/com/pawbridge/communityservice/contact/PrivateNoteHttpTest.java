package com.pawbridge.communityservice.contact;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.server.ResponseStatusException;

class PrivateNoteHttpTest {
    private final PrivateNoteService service = mock(PrivateNoteService.class);
    private MockMvc mvc;

    @BeforeEach
    void prepare() {
        mvc = MockMvcBuilders.standaloneSetup(new PrivateNoteController(service, mock(PrivateNoteStream.class)))
                .setControllerAdvice(new PrivateNoteExceptionAdvice())
                .addFilters(new PrivateNoteHttpBoundary()).build();
    }

    @Test
    void givenBlockedSend_whenPosted_thenForbiddenWithoutCaching() throws Exception {
        when(service.send(eq(7L), any())).thenThrow(new ResponseStatusException(HttpStatus.FORBIDDEN, "차단된 상대입니다."));
        mvc.perform(post("/api/v1/notes").header("X-User-Id", "7").contentType(MediaType.APPLICATION_JSON)
                .content("{\"recipientId\":8,\"body\":\"합성 본문\",\"requestId\":\"" + UUID.randomUUID() + "\"}"))
                .andExpect(status().isForbidden()).andExpect(header().string("Cache-Control", "no-store"));
    }

    @Test
    void givenSqlFailureContainingPrivateBody_whenPosted_thenReturnGenericErrorWithoutValues() throws Exception {
        when(service.send(eq(7L), any())).thenThrow(new DataIntegrityViolationException("SQL includes synthetic-private-body"));
        String response = mvc.perform(post("/api/v1/notes").header("X-User-Id", "7").contentType(MediaType.APPLICATION_JSON)
                .content("{\"recipientId\":8,\"body\":\"합성 본문\",\"requestId\":\"" + UUID.randomUUID() + "\"}"))
                .andExpect(status().isServiceUnavailable()).andReturn().getResponse().getContentAsString();
        assertThat(response).doesNotContain("synthetic-private-body", "SQL");
    }

    @Test
    void givenInvalidJsonOrUuid_whenRequested_thenBadRequestWithoutCallingService() throws Exception {
        mvc.perform(post("/api/v1/notes").header("X-User-Id", "7").contentType(MediaType.APPLICATION_JSON)
                .content("{\"body\":\"synthetic-private-body"))
                .andExpect(status().isBadRequest()).andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("synthetic-private-body"))));
        mvc.perform(get("/api/v1/notes/not-a-uuid").header("X-User-Id", "7"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }

    @Test
    void givenMissingGatewayIdentity_whenRequested_thenBadRequestWithoutCallingService() throws Exception {
        mvc.perform(get("/api/v1/notes")).andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }
}
