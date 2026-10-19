package com.pawbridge.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pawbridge.backend.boot.configuration.ReadSecurityConfiguration;
import com.pawbridge.backend.community.internal.note.application.ListNoteBlocks;
import com.pawbridge.backend.community.internal.note.application.NoteBlockListReader;
import com.pawbridge.backend.community.internal.note.application.NoteBlockReader;
import com.pawbridge.backend.community.internal.note.application.NoteNotificationReader;
import com.pawbridge.backend.community.internal.note.application.PrivateNoteReader;
import com.pawbridge.backend.community.internal.note.application.ReadNoteNotifications;
import com.pawbridge.backend.community.internal.note.application.ReadPrivateNote;
import com.pawbridge.backend.community.internal.note.web.NoteBlockListController;
import com.pawbridge.backend.community.internal.note.web.NoteBlockListExceptionHandler;
import com.pawbridge.backend.community.internal.note.web.NoteNotificationsController;
import com.pawbridge.backend.community.internal.note.web.NoteNotificationsExceptionHandler;
import com.pawbridge.backend.community.internal.note.web.PrivateNoteReadController;
import com.pawbridge.backend.community.internal.note.web.PrivateNoteReadExceptionHandler;
import com.pawbridge.backend.user.api.ContactMember;
import com.pawbridge.backend.user.api.ContactMemberQuery;
import com.pawbridge.backend.user.api.ContactMemberUnavailableException;
import com.pawbridge.backend.user.api.ContactMembersQuery;
import com.pawbridge.backend.user.internal.statistics.application.TotalUserCountQuery;
import com.pawbridge.backend.user.internal.statistics.web.TotalUserCountController;
import com.pawbridge.backend.user.internal.statistics.web.TotalUserCountExceptionHandler;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.TransactionTimedOutException;

@WebMvcTest(controllers = PrivateNoteReadController.class, excludeAutoConfiguration = org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration.class)
@ContextConfiguration(classes = {PrivateNoteReadController.class, PrivateNoteReadExceptionHandler.class, ReadPrivateNote.class,
        NoteNotificationsController.class, NoteNotificationsExceptionHandler.class, ReadNoteNotifications.class,
        NoteBlockListController.class, NoteBlockListExceptionHandler.class, ListNoteBlocks.class,
        TotalUserCountController.class, TotalUserCountExceptionHandler.class,
        ReadSecurityConfiguration.class, F3TestInputs.SecurityInputs.class})
class PrivateNoteReadHttpTest {
    private static final UUID NOTE = UUID.fromString("f2200000-0000-0000-0000-000000000001");
    private static final String PATH = "/api/v1/notes/" + NOTE;
    private static final String BODY = "  원문\n<태그>  ";
    private static final Instant NOW = Instant.parse("2031-01-01T00:00:00.999999Z");
    private static final Instant CREATED_AT = Instant.parse("2030-12-31T15:00:00.123456Z");
    private static final String SECURITY_CACHE = "no-cache, no-store, max-age=0, must-revalidate";

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @MockitoBean ContactMemberQuery members;
    @MockitoBean PrivateNoteReader notes;
    @MockitoBean NoteBlockReader blocks;
    @MockitoBean(name = "readClock") Clock clock;
    @MockitoBean ContactMembersQuery batches;
    @MockitoBean NoteBlockListReader blockLists;
    @MockitoBean NoteNotificationReader notifications;
    @MockitoBean TotalUserCountQuery counts;

    @ParameterizedTest
    @MethodSource("directions")
    void given__owned_inbox_or_sent_note_and_spoofed_identity__when__reading__then__preserve_exact_private_wire_counterpart_and_conditional_reply_lookup(
            String direction, long sender, long recipient, Instant readAt, boolean favorite,
            String contextType, String contextHref, boolean canReply) throws Exception {
        when(members.get(7)).thenReturn(active(7));
        when(clock.instant()).thenReturn(NOW);
        when(notes.find(7, NOTE, NOW)).thenReturn(Optional.of(new PrivateNoteReader.StoredNote(
                NOTE, sender, recipient, BODY, contextType, 13L, CREATED_AT, direction, readAt, favorite)));
        when(members.get(Long.MAX_VALUE)).thenReturn(new ContactMember(Long.MAX_VALUE, "  ", true, false));
        if (canReply) when(blocks.blocked(7, Long.MAX_VALUE)).thenReturn(false);

        var response = mvc.perform(authenticated(PATH)
                        .header("X-User-Id", "999").header("X-User-Role", "ROLE_ADMIN")
                        .param("userId", "999").contentType(MediaType.APPLICATION_JSON).content("{\"userId\":999}"))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().doesNotExist("Set-Cookie"))
                .andExpect(content().json(expectedNote(direction, readAt, favorite, Long.MAX_VALUE, "  ", canReply, contextHref), JsonCompareMode.STRICT))
                .andReturn().getResponse();

        var counterpart = mapper.readTree(response.getContentAsString()).path("data").path("counterpartId");
        assertThat(counterpart.isIntegralNumber()).isTrue();
        assertThat(counterpart.longValue()).isEqualTo(Long.MAX_VALUE);
        var order = inOrder(members, clock, notes, blocks);
        order.verify(members).get(7);
        order.verify(clock).instant();
        order.verify(notes).find(7, NOTE, NOW);
        order.verify(members).get(Long.MAX_VALUE);
        if (canReply) order.verify(blocks).blocked(7, Long.MAX_VALUE);
        else verifyNoInteractions(blocks);
        verifyNoMoreInteractions(members, clock, notes, blocks);
        verifyNoInteractions(batches, blockLists, notifications, counts);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    static Stream<Arguments> directions() {
        return Stream.of(Arguments.of("INBOX", Long.MAX_VALUE, 7L, null, true, "POST", "/community/13", true),
                Arguments.of("SENT", 7L, Long.MAX_VALUE, CREATED_AT.plusNanos(1000), false, "REPORT", "/reports/13", false));
    }

    @Test
    void given__blocked_active_inbox_counterpart__when__reading__then__return_body_with_can_reply_false_instead_of_recipient_403() throws Exception {
        prepareInbox(Long.MAX_VALUE);
        when(members.get(Long.MAX_VALUE)).thenReturn(active(Long.MAX_VALUE));
        when(blocks.blocked(7, Long.MAX_VALUE)).thenReturn(true);

        mvc.perform(authenticated(PATH)).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store")).andExpect(header().doesNotExist("Set-Cookie"))
                .andExpect(content().json(expectedNote("INBOX", null, false, Long.MAX_VALUE, "회원", false, null), JsonCompareMode.STRICT));

        verify(members).get(7); verify(members).get(Long.MAX_VALUE); verify(clock).instant();
        verify(notes).find(7, NOTE, NOW); verify(blocks).blocked(7, Long.MAX_VALUE);
        verifyNoMoreInteractions(members, clock, notes, blocks);
        verifyNoInteractions(batches, blockLists, notifications, counts);
    }

    @ParameterizedTest
    @ValueSource(strings = {"NULL", "MISSING", "PENDING"})
    void given__null_missing_or_pending_counterpart__when__reading_owned_note__then__keep_body_and_null_display_id_without_block_lookup(String state) throws Exception {
        Long counterpart = state.equals("NULL") ? null : Long.MAX_VALUE;
        prepareInbox(counterpart);
        if (counterpart != null) when(members.get(counterpart)).thenReturn(
                new ContactMember(counterpart, "원문을 노출하지 않음", false, state.equals("PENDING")));

        mvc.perform(authenticated(PATH)).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store")).andExpect(header().doesNotExist("Set-Cookie"))
                .andExpect(content().json(expectedNote("INBOX", null, false, null, "탈퇴한 회원", false, null), JsonCompareMode.STRICT));

        verify(members).get(7); if (counterpart != null) verify(members).get(counterpart);
        verify(clock).instant(); verify(notes).find(7, NOTE, NOW);
        verifyNoMoreInteractions(members, clock, notes);
        verifyNoInteractions(blocks, batches, blockLists, notifications, counts);
    }

    @Test
    void given__no_owned_unexpired_mailbox__when__reading__then__return_404_before_counterpart_or_block_lookup() throws Exception {
        when(members.get(7)).thenReturn(active(7)); when(clock.instant()).thenReturn(NOW);
        when(notes.find(7, NOTE, NOW)).thenReturn(Optional.empty());

        mvc.perform(authenticated(PATH)).andExpect(status().isNotFound())
                .andExpect(header().string("Cache-Control", "no-store")).andExpect(header().doesNotExist("Set-Cookie"))
                .andExpect(content().json("{\"message\":\"쪽지를 찾을 수 없습니다.\"}", JsonCompareMode.STRICT));

        var order = inOrder(members, clock, notes);
        order.verify(members).get(7); order.verify(clock).instant(); order.verify(notes).find(7, NOTE, NOW);
        verifyNoMoreInteractions(members, clock, notes);
        verifyNoInteractions(blocks, batches, blockLists, notifications, counts);
    }

    @Test
    void given__inactive_current_member__when__reading__then__return_403_before_clock_note_or_counterpart() throws Exception {
        when(members.get(7)).thenReturn(new ContactMember(7L, "탈퇴한 회원", false, true));

        mvc.perform(authenticated(PATH)).andExpect(status().isForbidden())
                .andExpect(header().string("Cache-Control", "no-store")).andExpect(header().doesNotExist("Set-Cookie"))
                .andExpect(content().json("{\"message\":\"이용할 수 없는 회원입니다.\"}", JsonCompareMode.STRICT));

        verify(members).get(7); verifyNoMoreInteractions(members);
        verifyNoInteractions(clock, notes, blocks, batches, blockLists, notifications, counts);
    }

    @ParameterizedTest
    @MethodSource("rejectedAuthorizations")
    void given__missing_or_invalid_jwt_and_malformed_note_id__when__requesting__then__authenticate_before_uuid_binding_and_all_dependencies(String authorization, String message) throws Exception {
        var request = get("/api/v1/notes/not-a-uuid").header("X-User-Id", "7");
        if (authorization != null) request.header("Authorization", authorization);
        mvc.perform(request).andExpect(status().isUnauthorized())
                .andExpect(header().string("Cache-Control", SECURITY_CACHE)).andExpect(header().doesNotExist("Set-Cookie"))
                .andExpect(content().json(mapper.createObjectNode().put("code", 401).put("message", message)
                        .putNull("data").toString(), JsonCompareMode.STRICT));

        noDependencies();
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    static Stream<Arguments> rejectedAuthorizations() {
        return Stream.of(Arguments.of(null, "인증 토큰이 필요합니다."),
                Arguments.of("Bearer invalid", "유효하지 않거나 만료된 토큰입니다."));
    }

    @Test
    void given__authenticated_malformed_note_id__when__binding__then__return_message_only_400_before_user_clock_or_note() throws Exception {
        mvc.perform(authenticated("/api/v1/notes/not-a-uuid")).andExpect(status().isBadRequest())
                .andExpect(header().string("Cache-Control", "no-store")).andExpect(header().doesNotExist("Set-Cookie"))
                .andExpect(content().json("{\"message\":\"요청 형식과 로그인 정보를 확인해 주세요.\"}", JsonCompareMode.STRICT));
        noDependencies();
    }

    @ParameterizedTest
    @ValueSource(strings = {"MISSING", "INVALID", "VALID"})
    void given__unexposed_stream_extension_with_no_bad_or_valid_jwt__when__requesting__then__retain_default_403_without_authentication_binding_or_sse_work(String token) throws Exception {
        var request = get("/api/v1/notes/stream/extra").accept(MediaType.TEXT_EVENT_STREAM).header("X-User-Id", "7");
        if (!token.equals("MISSING")) request.header("Authorization", token.equals("INVALID")
                ? "Bearer invalid" : "Bearer " + F3TestInputs.token(7, "ROLE_USER"));

        mvc.perform(request).andExpect(status().isForbidden())
                .andExpect(header().string("Cache-Control", SECURITY_CACHE)).andExpect(header().doesNotExist("Set-Cookie"))
                .andExpect(content().json("{\"code\":403,\"message\":\"접근할 수 없습니다.\",\"data\":null}", JsonCompareMode.STRICT));
        noDependencies();
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @ParameterizedTest
    @MethodSource("dependencyFailures")
    void given__current_clock_note_counterpart_or_block_failure__when__reading__then__preserve_source_specific_503_without_partial_body(
            String phase, RuntimeException failure, String message) throws Exception {
        if (phase.equals("CURRENT")) when(members.get(7)).thenThrow(failure);
        else {
            when(members.get(7)).thenReturn(active(7));
            if (phase.equals("CLOCK")) when(clock.instant()).thenThrow(failure);
            else {
                when(clock.instant()).thenReturn(NOW);
                if (phase.equals("COUNTERPART") || phase.equals("BLOCK_DAO")) {
                    when(notes.find(7, NOTE, NOW)).thenReturn(Optional.of(inbox(Long.MAX_VALUE)));
                    if (phase.equals("COUNTERPART")) when(members.get(Long.MAX_VALUE)).thenThrow(failure);
                    else {
                        when(members.get(Long.MAX_VALUE)).thenReturn(active(Long.MAX_VALUE));
                        when(blocks.blocked(7, Long.MAX_VALUE)).thenThrow(failure);
                    }
                } else when(notes.find(7, NOTE, NOW)).thenThrow(failure);
            }
        }

        mvc.perform(authenticated(PATH)).andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Cache-Control", "no-store")).andExpect(header().doesNotExist("Set-Cookie"))
                .andExpect(content().json(mapper.writeValueAsString(Map.of("message", message)), JsonCompareMode.STRICT));

        verify(members).get(7);
        if (phase.equals("CURRENT")) verifyNoInteractions(clock, notes, blocks);
        else {
            verify(clock).instant();
            if (phase.equals("CLOCK")) verifyNoInteractions(notes, blocks);
            else {
                verify(notes).find(7, NOTE, NOW);
                if (phase.equals("COUNTERPART") || phase.equals("BLOCK_DAO")) verify(members).get(Long.MAX_VALUE);
                if (phase.equals("BLOCK_DAO")) verify(blocks).blocked(7, Long.MAX_VALUE);
                else verifyNoInteractions(blocks);
            }
        }
        verifyNoMoreInteractions(members, clock, notes, blocks);
        verifyNoInteractions(batches, blockLists, notifications, counts);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    static Stream<Arguments> dependencyFailures() {
        String member = "회원 정보를 확인할 수 없습니다. 잠시 후 다시 시도해 주세요.";
        String storage = "쪽지 저장소를 사용할 수 없습니다. 잠시 후 다시 시도해 주세요.";
        String generic = "쪽지를 처리하지 못했습니다. 잠시 후 다시 시도해 주세요.";
        return Stream.of(
                Arguments.of("CURRENT", new ContactMemberUnavailableException(new DataAccessResourceFailureException("synthetic-private-current")), member),
                Arguments.of("COUNTERPART", new ContactMemberUnavailableException(new TransactionSystemException("synthetic-private-counterpart")), member),
                Arguments.of("NOTE_DAO", new DataAccessResourceFailureException("synthetic-private-note", new SQLException("synthetic-private-password")), storage),
                Arguments.of("BLOCK_DAO", new DataAccessResourceFailureException("synthetic-private-block"), storage),
                Arguments.of("BEGIN", new CannotCreateTransactionException("synthetic-private-begin"), storage),
                Arguments.of("COMMIT", new TransactionSystemException("synthetic-private-commit"), storage),
                Arguments.of("TIMEOUT", new TransactionTimedOutException("synthetic-private-timeout"), storage),
                Arguments.of("USAGE", new IllegalTransactionStateException("synthetic-private-usage"), generic),
                Arguments.of("RUNTIME", new IllegalStateException("synthetic-private-body"), generic),
                Arguments.of("CLOCK", new IllegalStateException("synthetic-private-clock"), generic));
    }

    @Test
    void given__static_notification_and_block_routes__when__reading__then__prefer_their_mappings_and_handlers_over_uuid_detail() throws Exception {
        when(members.get(7)).thenReturn(active(7)); when(clock.instant()).thenReturn(NOW);
        when(notifications.read(7, null, NOW)).thenReturn(List.of()); when(notifications.unread(7, NOW)).thenReturn(0L);
        when(blockLists.count(7)).thenReturn(0L); when(blockLists.read(7, 0)).thenReturn(List.of());

        mvc.perform(authenticated("/api/v1/notes/notifications")).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store")).andExpect(header().doesNotExist("Set-Cookie"))
                .andExpect(content().json("{\"code\":200,\"message\":\"요청이 성공적으로 처리되었습니다.\",\"data\":{\"content\":[],\"nextCursor\":null,\"unreadCount\":0}}", JsonCompareMode.STRICT));
        mvc.perform(authenticated("/api/v1/notes/blocks")).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store")).andExpect(header().doesNotExist("Set-Cookie"))
                .andExpect(content().json("{\"code\":200,\"message\":\"요청이 성공적으로 처리되었습니다.\",\"data\":{\"content\":[],\"totalElements\":0,\"totalPages\":0,\"number\":0}}", JsonCompareMode.STRICT));
        mvc.perform(authenticated("/api/v1/notes/blocks").param("page", "-1")).andExpect(status().isBadRequest())
                .andExpect(header().string("Cache-Control", "no-store")).andExpect(header().doesNotExist("Set-Cookie"))
                .andExpect(content().json("{\"message\":\"페이지 범위를 확인해 주세요.\"}", JsonCompareMode.STRICT));

        verify(members, times(2)).get(7); verify(clock).instant();
        verify(notifications).read(7, null, NOW); verify(notifications).unread(7, NOW);
        verify(blockLists).count(7); verify(blockLists).read(7, 0);
        verifyNoMoreInteractions(members, clock, notifications, blockLists);
        verifyNoInteractions(notes, blocks, batches, counts);
    }

    @ParameterizedTest
    @ValueSource(strings = {"GET /api/notes/f2200000-0000-0000-0000-000000000001",
            "GET /api/v1/notes/f2200000-0000-0000-0000-000000000001/", "HEAD /api/v1/notes/f2200000-0000-0000-0000-000000000001",
            "OPTIONS /api/v1/notes/f2200000-0000-0000-0000-000000000001", "POST /api/v1/notes/f2200000-0000-0000-0000-000000000001",
            "PUT /api/v1/notes/f2200000-0000-0000-0000-000000000001/read/extra", "PUT /api/v1/notes/f2200000-0000-0000-0000-000000000001/favorite/extra",
            "DELETE /api/v1/notes/f2200000-0000-0000-0000-000000000001/extra"})
    void given__unexposed_neighbor_alias_or_method__when__requesting_with_valid_jwt__then__retain_default_deny_without_note_or_writer_work(String request) throws Exception {
        String[] parts = request.split(" ", 2);
        mvc.perform(request(HttpMethod.valueOf(parts[0]), parts[1]).header("Authorization", "Bearer " + F3TestInputs.token(7, "ROLE_USER")))
                .andExpect(status().isForbidden()).andExpect(header().string("Cache-Control", SECURITY_CACHE))
                .andExpect(header().doesNotExist("Set-Cookie"));
        noDependencies();
    }

    @Test
    void given__existing_admin_count_permission_and_runtime_error__when__requesting__then__keep_admin_403_and_common_500_outside_note_advice() throws Exception {
        when(counts.count()).thenThrow(new IllegalStateException("synthetic-private-count"));

        mvc.perform(authenticated("/api/v1/admin/users/stats/total-users")).andExpect(status().isForbidden())
                .andExpect(header().string("Cache-Control", SECURITY_CACHE)).andExpect(header().doesNotExist("Set-Cookie"))
                .andExpect(content().json("{\"code\":403,\"message\":\"관리자 권한이 필요합니다.\",\"data\":null}", JsonCompareMode.STRICT));
        mvc.perform(get("/api/v1/admin/users/stats/total-users").header("Authorization", "Bearer " + F3TestInputs.token(7, "ROLE_ADMIN")))
                .andExpect(status().isInternalServerError()).andExpect(header().string("Cache-Control", SECURITY_CACHE))
                .andExpect(header().doesNotExist("Set-Cookie"))
                .andExpect(content().json("{\"code\":500,\"message\":\"서버 에러!\",\"data\":null}", JsonCompareMode.STRICT));

        verify(counts).count(); verifyNoMoreInteractions(counts);
        verifyNoInteractions(members, clock, notes, blocks, batches, blockLists, notifications);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    private String expectedNote(String direction, Instant readAt, boolean favorite, Long counterpart,
                                String nickname, boolean canReply, String contextHref) throws Exception {
        var data = mapper.createObjectNode().put("noteId", NOTE.toString()).put("body", BODY).put("direction", direction)
                .put("createdAt", CREATED_AT.toString()).put("readAt", readAt == null ? null : readAt.toString())
                .put("favorite", favorite).put("counterpartNickname", nickname).put("canReply", canReply).put("contextHref", contextHref);
        if (counterpart == null) data.putNull("counterpartId"); else data.put("counterpartId", counterpart);
        return mapper.writeValueAsString(Map.of("code", 200, "message", "요청이 성공적으로 처리되었습니다.", "data", data));
    }

    private void prepareInbox(Long counterpart) {
        when(members.get(7)).thenReturn(active(7)); when(clock.instant()).thenReturn(NOW);
        when(notes.find(7, NOTE, NOW)).thenReturn(Optional.of(inbox(counterpart)));
    }

    private static PrivateNoteReader.StoredNote inbox(Long counterpart) {
        return new PrivateNoteReader.StoredNote(NOTE, counterpart, 7L, BODY, null, null, CREATED_AT, "INBOX", null, false);
    }

    private MockHttpServletRequestBuilder authenticated(String path) {
        return get(path).header("Authorization", "Bearer " + F3TestInputs.token(7, "ROLE_USER"));
    }

    private void noDependencies() { verifyNoInteractions(members, clock, notes, blocks, batches, blockLists, notifications, counts); }

    private static ContactMember active(long id) { return new ContactMember(id, "회원", true, false); }
}
