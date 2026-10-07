package com.pawbridge.communityservice.contact;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.pawbridge.communityservice.contact.PrivateNoteModels.Notifications;
import com.pawbridge.communityservice.contact.PrivateNoteModels.Receipt;
import com.pawbridge.communityservice.contact.PrivateNoteModels.SendNote;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

class PrivateNoteHttpTest {
    private final PrivateNoteService service = mock(PrivateNoteService.class);
    private final PrivateNoteStream privateNoteStream = mock(PrivateNoteStream.class);
    private MockMvc mvc;

    @BeforeEach
    void prepare() {
        mvc = MockMvcBuilders.standaloneSetup(new PrivateNoteController(service, privateNoteStream))
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

    @Test
    void givenSendRequest_whenPosted_thenBindSenderAndReturnReceipt() throws Exception {
        UUID requestId = UUID.randomUUID();
        UUID noteId = UUID.randomUUID();
        SendNote sendNoteRequest = new SendNote(8L, "합성 본문", requestId, null, null, null);
        when(service.send(7L, sendNoteRequest)).thenReturn(new Receipt(noteId));

        mvc.perform(post("/api/v1/notes")
                        .header("X-User-Id", "7")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"recipientId\":8,\"body\":\"합성 본문\",\"requestId\":\"" + requestId + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.noteId").value(noteId.toString()));

        verify(service).send(7L, sendNoteRequest);
    }

    @Test
    void givenDefaultOrExplicitListOptions_whenRequested_thenPreserveQueryParameterContract() throws Exception {
        mvc.perform(get("/api/v1/notes").header("X-User-Id", "7"))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/notes")
                        .header("X-User-Id", "7")
                        .param("box", "SENT")
                        .param("favorites", "true")
                        .param("page", "2"))
                .andExpect(status().isOk());

        verify(service).list(7L, "INBOX", false, 0);
        verify(service).list(7L, "SENT", true, 2);
    }

    @Test
    void givenRecipientAndBlockPaths_whenRequested_thenBindTargetSeparatelyFromCurrentUser() throws Exception {
        mvc.perform(get("/api/v1/notes/recipients/8").header("X-User-Id", "7"))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/notes/blocks").header("X-User-Id", "7").param("page", "2"))
                .andExpect(status().isOk());
        mvc.perform(put("/api/v1/notes/blocks/8").header("X-User-Id", "7"))
                .andExpect(status().isOk());
        mvc.perform(delete("/api/v1/notes/blocks/8").header("X-User-Id", "7"))
                .andExpect(status().isOk());

        verify(service).recipient(7L, 8L);
        verify(service).blocks(7L, 2);
        verify(service).block(7L, 8L);
        verify(service).unblock(7L, 8L);
    }

    @Test
    void givenNoteId_whenGettingReadingFavoritingOrDeleting_thenBindExistingIdPath() throws Exception {
        UUID noteId = UUID.randomUUID();
        String notePath = "/api/v1/notes/" + noteId;

        mvc.perform(get(notePath).header("X-User-Id", "7"))
                .andExpect(status().isOk());
        mvc.perform(put(notePath + "/read").header("X-User-Id", "7"))
                .andExpect(status().isOk());
        mvc.perform(put(notePath + "/favorite")
                        .header("X-User-Id", "7")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"favorite\":true}"))
                .andExpect(status().isOk());
        mvc.perform(delete(notePath).header("X-User-Id", "7"))
                .andExpect(status().isOk());

        verify(service).get(7L, noteId);
        verify(service).read(7L, noteId);
        verify(service).favorite(7L, noteId, true);
        verify(service).delete(7L, noteId);
    }

    @Test
    void givenNotificationCursor_whenRequested_thenBindCursorAndReturnUnreadCount() throws Exception {
        UUID cursor = UUID.randomUUID();
        when(service.notifications(7L, cursor)).thenReturn(new Notifications(List.of(), null, 3L));

        mvc.perform(get("/api/v1/notes/notifications")
                        .header("X-User-Id", "7")
                        .param("cursor", cursor.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.unreadCount").value(3));

        verify(service).notifications(7L, cursor);
    }

    @Test
    void givenFullStreamCapacity_whenRequested_thenPreserveRetryAfterAndPrivateErrorBody() throws Exception {
        Instant tokenExpiresAt = Instant.parse("2026-10-07T12:00:00Z");
        HttpHeaders retryHeaders = new HttpHeaders();
        retryHeaders.set("Retry-After", "15");
        ResponseStatusException full = new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "알림 연결이 너무 많습니다.") {
            @Override public HttpHeaders getHeaders() { return retryHeaders; }
        };
        when(privateNoteStream.open(7L, tokenExpiresAt)).thenThrow(full);

        mvc.perform(get("/api/v1/notes/stream")
                        .header("X-User-Id", "7")
                        .header("X-Auth-Expires-At", tokenExpiresAt.toEpochMilli()))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "15"))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.message").value("알림 연결이 너무 많습니다."));
    }

    @Test
    void givenAuthenticatedStream_whenRequested_thenCheckMemberBeforeOpeningWithTokenExpiry() throws Exception {
        Instant tokenExpiresAt = Instant.parse("2026-10-06T12:00:00Z");
        SseEmitter emitter = new SseEmitter(60_000L);
        emitter.send(SseEmitter.event().name("ready").data("{}"));
        when(privateNoteStream.open(7L, tokenExpiresAt)).thenReturn(emitter);

        try {
            mvc.perform(get("/api/v1/notes/stream")
                            .header("X-User-Id", "7")
                            .header("X-Auth-Expires-At", tokenExpiresAt.toEpochMilli()))
                    .andExpect(status().isOk())
                    .andExpect(request().asyncStarted())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM))
                    .andExpect(header().string("Cache-Control", "no-store"))
                    .andExpect(header().string("X-Accel-Buffering", "no"));

            var invocationOrder = inOrder(service, privateNoteStream);
            invocationOrder.verify(service).requireActive(7L);
            invocationOrder.verify(privateNoteStream).open(7L, tokenExpiresAt);
        } finally {
            emitter.complete();
        }
    }
}
