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
import com.pawbridge.backend.community.internal.note.application.NoteBlockStatusReader;
import com.pawbridge.backend.community.internal.note.application.NoteNotificationReader;
import com.pawbridge.backend.community.internal.note.application.ReadBlockedAuthors;
import com.pawbridge.backend.community.internal.note.application.ReadNoteNotifications;
import com.pawbridge.backend.community.internal.note.application.ReadNoteRecipient;
import com.pawbridge.backend.community.internal.note.web.NoteBlockListController;
import com.pawbridge.backend.community.internal.note.web.NoteBlockListExceptionHandler;
import com.pawbridge.backend.community.internal.note.web.NoteBlockStatusController;
import com.pawbridge.backend.community.internal.note.web.NoteBlockStatusExceptionHandler;
import com.pawbridge.backend.community.internal.note.web.NoteNotificationsController;
import com.pawbridge.backend.community.internal.note.web.NoteNotificationsExceptionHandler;
import com.pawbridge.backend.community.internal.note.web.NoteRecipientController;
import com.pawbridge.backend.community.internal.note.web.NoteRecipientExceptionHandler;
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

@WebMvcTest(controllers = NoteNotificationsController.class, excludeAutoConfiguration = org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration.class)
@ContextConfiguration(classes = {NoteNotificationsController.class, NoteNotificationsExceptionHandler.class, ReadNoteNotifications.class,
        NoteRecipientController.class, NoteRecipientExceptionHandler.class, ReadNoteRecipient.class,
        NoteBlockStatusController.class, NoteBlockStatusExceptionHandler.class, ReadBlockedAuthors.class,
        NoteBlockListController.class, NoteBlockListExceptionHandler.class, ListNoteBlocks.class,
        TotalUserCountController.class, TotalUserCountExceptionHandler.class,
        ReadSecurityConfiguration.class, F3TestInputs.SecurityInputs.class})
class NoteNotificationsHttpTest {
    private static final String PATH = "/api/v1/notes/notifications";
    private static final Instant NOW = Instant.parse("2026-10-19T15:00:00.999999Z");
    private static final Instant CREATED_AT = Instant.parse("2026-10-19T15:00:00.123456Z");
    private static final UUID FIRST = UUID.fromString("ffffffff-ffff-ffff-ffff-fffffffffff1");
    private static final UUID SECOND = UUID.fromString("ffffffff-ffff-ffff-ffff-fffffffffff2");
    private static final UUID THIRD = UUID.fromString("ffffffff-ffff-ffff-ffff-fffffffffff3");

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @MockitoBean ContactMemberQuery members;
    @MockitoBean ContactMembersQuery batches;
    @MockitoBean NoteNotificationReader notes;
    @MockitoBean(name = "readClock") Clock clock;
    @MockitoBean NoteBlockReader recipientBlocks;
    @MockitoBean NoteBlockStatusReader statusBlocks;
    @MockitoBean NoteBlockListReader blocks;
    @MockitoBean TotalUserCountQuery counts;

    @ParameterizedTest
    @ValueSource(strings = {"ROLE_USER", "ROLE_ADMIN", "ROLE_SHELTER"})
    void given__ordinary_role_and_spoofed_identity__when__reading_notifications__then__keep_exact_nullable_wire_raw_actor_id_and_one_shared_instant(String role) throws Exception {
        when(members.get(7)).thenReturn(active(7));
        when(clock.instant()).thenReturn(NOW);
        when(notes.read(7, null, NOW)).thenReturn(List.of(
                new NoteNotificationReader.Row(FIRST, Long.MAX_VALUE, CREATED_AT, null),
                new NoteNotificationReader.Row(SECOND, 8L, CREATED_AT.minusNanos(1000), NOW),
                new NoteNotificationReader.Row(THIRD, null, CREATED_AT.minusNanos(2000), null)));
        when(batches.getAll(List.of(Long.MAX_VALUE, 8L))).thenReturn(List.of(
                new ContactMember(8L, "원래 닉네임을 노출하지 않음", false, true),
                new ContactMember(Long.MAX_VALUE, "  ", true, false)));
        when(notes.unread(7, NOW)).thenReturn(5_000_000_000L);

        var response = mvc.perform(authenticatedNotifications(role)
                        .header("X-User-Id", "999").header("X-User-Role", "ROLE_ADMIN")
                        .param("userId", "999").param("role", "ROLE_ADMIN")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"userId\":999,\"cursor\":\"forged\"}"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().doesNotExist("Set-Cookie"))
                .andExpect(content().json("{\"code\":200,\"message\":\"요청이 성공적으로 처리되었습니다.\",\"data\":{"
                        + "\"content\":[{\"noteId\":\"" + FIRST + "\",\"kind\":\"PRIVATE_NOTE\",\"actorId\":" + Long.MAX_VALUE
                        + ",\"actorNickname\":\"  \",\"createdAt\":\"2026-10-19T15:00:00.123456Z\",\"read\":false,\"href\":\"/notes/" + FIRST + "\"},"
                        + "{\"noteId\":\"" + SECOND + "\",\"kind\":\"PRIVATE_NOTE\",\"actorId\":null,\"actorNickname\":\"탈퇴한 회원\","
                        + "\"createdAt\":\"2026-10-19T15:00:00.123455Z\",\"read\":true,\"href\":\"/notes/" + SECOND + "\"},"
                        + "{\"noteId\":\"" + THIRD + "\",\"kind\":\"PRIVATE_NOTE\",\"actorId\":null,\"actorNickname\":\"탈퇴한 회원\","
                        + "\"createdAt\":\"2026-10-19T15:00:00.123454Z\",\"read\":false,\"href\":\"/notes/" + THIRD + "\"}],"
                        + "\"nextCursor\":null,\"unreadCount\":5000000000}}", JsonCompareMode.STRICT))
                .andReturn().getResponse();

        var actorId = mapper.readTree(response.getContentAsString()).path("data").path("content").get(0).path("actorId");
        assertThat(actorId.isIntegralNumber()).isTrue();
        assertThat(actorId.longValue()).isEqualTo(Long.MAX_VALUE);
        var order = inOrder(members, clock, notes, batches);
        order.verify(members).get(7);
        order.verify(clock).instant();
        order.verify(notes).read(7, null, NOW);
        order.verify(batches).getAll(List.of(Long.MAX_VALUE, 8L));
        order.verify(notes).unread(7, NOW);
        verifyNoMoreInteractions(members, clock, notes, batches);
        verifyNoInteractions(recipientBlocks, statusBlocks, blocks, counts);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void given__valid_cursor_and_empty_page__when__reading__then__forward_uuid_and_keep_null_next_cursor_and_raw_unread_count_without_batch() throws Exception {
        when(members.get(7)).thenReturn(active(7));
        when(clock.instant()).thenReturn(NOW);
        when(notes.read(7, FIRST, NOW)).thenReturn(List.of());
        when(notes.unread(7, NOW)).thenReturn(Long.MAX_VALUE);

        mvc.perform(authenticatedNotifications("ROLE_USER").param("cursor", FIRST.toString()))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().doesNotExist("Set-Cookie"))
                .andExpect(content().json("{\"code\":200,\"message\":\"요청이 성공적으로 처리되었습니다.\",\"data\":{"
                        + "\"content\":[],\"nextCursor\":null,\"unreadCount\":" + Long.MAX_VALUE + "}}", JsonCompareMode.STRICT));

        var order = inOrder(members, clock, notes);
        order.verify(members).get(7);
        order.verify(clock).instant();
        order.verify(notes).read(7, FIRST, NOW);
        order.verify(notes).unread(7, NOW);
        verifyNoMoreInteractions(members, clock, notes);
        verifyNoInteractions(batches, recipientBlocks, statusBlocks, blocks, counts);
    }

    @ParameterizedTest
    @MethodSource("rejectedAuthorizations")
    void given__missing_or_invalid_jwt_and_bad_cursor__when__requesting__then__authenticate_before_binding_and_every_dependency(String authorization, String message) throws Exception {
        var request = get(PATH).param("cursor", "not-a-uuid").header("X-User-Id", "7");
        if (authorization != null) request.header("Authorization", authorization);

        mvc.perform(request).andExpect(status().isUnauthorized())
                .andExpect(header().doesNotExist("Set-Cookie"))
                .andExpect(content().json(mapper.writeValueAsString(
                        new NoteNotificationsController.Response<>(401, message, null)), JsonCompareMode.STRICT));

        verifyNoInteractions(members, batches, clock, notes, recipientBlocks, statusBlocks, blocks, counts);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    static Stream<Arguments> rejectedAuthorizations() {
        return Stream.of(Arguments.of(null, "인증 토큰이 필요합니다."),
                Arguments.of("Bearer invalid", "유효하지 않거나 만료된 토큰입니다."));
    }

    @Test
    void given__authenticated_unconvertible_cursor__when__binding__then__return_message_only_400_before_member_or_clock() throws Exception {
        mvc.perform(authenticatedNotifications("ROLE_USER").param("cursor", "not-a-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().doesNotExist("Set-Cookie"))
                .andExpect(content().json("{\"message\":\"요청 형식과 로그인 정보를 확인해 주세요.\"}", JsonCompareMode.STRICT));

        verifyNoInteractions(members, batches, clock, notes, recipientBlocks, statusBlocks, blocks, counts);
    }

    @Test
    void given__pending_current_member__when__reading__then__return_inactive_403_before_clock_list_batch_or_unread() throws Exception {
        when(members.get(7)).thenReturn(new ContactMember(7L, "탈퇴한 회원", false, true));

        mvc.perform(authenticatedNotifications("ROLE_USER"))
                .andExpect(status().isForbidden())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().doesNotExist("Set-Cookie"))
                .andExpect(content().json("{\"message\":\"이용할 수 없는 회원입니다.\"}", JsonCompareMode.STRICT));

        verify(members).get(7);
        verifyNoMoreInteractions(members);
        verifyNoInteractions(batches, clock, notes, recipientBlocks, statusBlocks, blocks, counts);
    }

    @ParameterizedTest
    @MethodSource("dependencyFailures")
    void given__member_list_clock_or_unread_failure__when__reading__then__retain_source_specific_503_and_stop_later_phases(
            String phase, RuntimeException failure, String message) throws Exception {
        if (phase.equals("CURRENT")) when(members.get(7)).thenThrow(failure);
        else {
            when(members.get(7)).thenReturn(active(7));
            if (phase.equals("CLOCK")) when(clock.instant()).thenThrow(failure);
            else {
                when(clock.instant()).thenReturn(NOW);
                if (phase.equals("BATCH")) {
                    when(notes.read(7, null, NOW)).thenReturn(List.of(new NoteNotificationReader.Row(FIRST, 8L, CREATED_AT, null)));
                    when(batches.getAll(List.of(8L))).thenThrow(failure);
                } else if (phase.equals("UNREAD_DAO")) {
                    when(notes.read(7, null, NOW)).thenReturn(List.of());
                    when(notes.unread(7, NOW)).thenThrow(failure);
                } else when(notes.read(7, null, NOW)).thenThrow(failure);
            }
        }

        mvc.perform(authenticatedNotifications("ROLE_USER"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().doesNotExist("Set-Cookie"))
                .andExpect(content().json(mapper.writeValueAsString(Map.of("message", message)), JsonCompareMode.STRICT));

        verify(members).get(7);
        if (phase.equals("CURRENT")) verifyNoInteractions(clock, notes, batches);
        else {
            verify(clock).instant();
            if (phase.equals("CLOCK")) verifyNoInteractions(notes, batches);
            else {
                verify(notes).read(7, null, NOW);
                if (phase.equals("BATCH")) verify(batches).getAll(List.of(8L));
                else {
                    verifyNoInteractions(batches);
                    if (phase.equals("UNREAD_DAO")) verify(notes).unread(7, NOW);
                }
            }
        }
        verifyNoMoreInteractions(members, clock, notes, batches);
        verifyNoInteractions(recipientBlocks, statusBlocks, blocks, counts);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    static Stream<Arguments> dependencyFailures() {
        String member = "회원 정보를 확인할 수 없습니다. 잠시 후 다시 시도해 주세요.";
        String storage = "쪽지 저장소를 사용할 수 없습니다. 잠시 후 다시 시도해 주세요.";
        String generic = "쪽지를 처리하지 못했습니다. 잠시 후 다시 시도해 주세요.";
        return Stream.of(
                Arguments.of("CURRENT", new ContactMemberUnavailableException(new DataAccessResourceFailureException("synthetic-private-caller")), member),
                Arguments.of("BATCH", new ContactMemberUnavailableException(new TransactionSystemException("synthetic-private-batch-commit")), member),
                Arguments.of("LIST_DAO", new DataAccessResourceFailureException("synthetic-private-list", new SQLException("synthetic-private-password")), storage),
                Arguments.of("UNREAD_DAO", new DataAccessResourceFailureException("synthetic-private-unread"), storage),
                Arguments.of("BEGIN", new CannotCreateTransactionException("synthetic-private-begin"), storage),
                Arguments.of("COMMIT", new TransactionSystemException("synthetic-private-commit"), storage),
                Arguments.of("TIMEOUT", new TransactionTimedOutException("synthetic-private-timeout"), storage),
                Arguments.of("USAGE", new IllegalTransactionStateException("synthetic-private-usage"), generic),
                Arguments.of("RUNTIME", new IllegalStateException("Bearer synthetic-private-token"), generic),
                Arguments.of("CLOCK", new IllegalStateException("synthetic-private-clock"), generic));
    }

    @ParameterizedTest
    @ValueSource(strings = {"GET /api/v1/notes/stream/extra", "GET /api/v1/notes/notifications/", "GET /api/notes/notifications",
            "HEAD /api/v1/notes/notifications", "OPTIONS /api/v1/notes/notifications", "POST /api/v1/notes/notifications",
            "PUT /api/v1/notes/notifications", "DELETE /api/v1/notes/notifications"})
    void given__unexposed_neighbor_path_or_method__when__requesting_with_a_valid_token__then__deny_before_all_dependencies(String request) throws Exception {
        String[] parts = request.split(" ", 2);

        mvc.perform(request(HttpMethod.valueOf(parts[0]), parts[1])
                        .header("Authorization", "Bearer " + F3TestInputs.token(7, "ROLE_USER")))
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist("Set-Cookie"));

        verifyNoInteractions(members, batches, clock, notes, recipientBlocks, statusBlocks, blocks, counts);
    }

    @Test
    void given__existing_recipient_status_block_list_and_admin_paths__when__their_own_policies_run__then__retain_scoped_wire_without_notification_work() throws Exception {
        when(members.get(7)).thenReturn(active(7));
        when(blocks.count(7)).thenReturn(0L);
        when(blocks.read(7, 0)).thenReturn(List.of());

        mvc.perform(get("/api/v1/notes/recipients/7").header("Authorization", "Bearer " + F3TestInputs.token(7, "ROLE_USER")))
                .andExpect(status().isForbidden())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(content().json("{\"message\":\"이 상대에게 쪽지를 보낼 수 없습니다.\"}", JsonCompareMode.STRICT));
        mvc.perform(get("/api/v1/notes/blocks/status").param("memberIds", "0")
                        .header("Authorization", "Bearer " + F3TestInputs.token(7, "ROLE_USER")))
                .andExpect(status().isBadRequest())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(content().json("{\"message\":\"작성자 식별자는 1~100개의 양수여야 합니다.\"}", JsonCompareMode.STRICT));
        mvc.perform(get("/api/v1/notes/blocks").header("Authorization", "Bearer " + F3TestInputs.token(7, "ROLE_USER")))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(content().json("{\"code\":200,\"message\":\"요청이 성공적으로 처리되었습니다.\",\"data\":{"
                        + "\"content\":[],\"totalElements\":0,\"totalPages\":0,\"number\":0}}", JsonCompareMode.STRICT));
        mvc.perform(get("/api/v1/admin/users/stats/total-users").header("Authorization", "Bearer " + F3TestInputs.token(7, "ROLE_USER")))
                .andExpect(status().isForbidden())
                .andExpect(content().json("{\"code\":403,\"message\":\"관리자 권한이 필요합니다.\",\"data\":null}", JsonCompareMode.STRICT));

        verify(members, times(2)).get(7);
        verifyNoMoreInteractions(members);
        verify(blocks).count(7);
        verify(blocks).read(7, 0);
        verifyNoMoreInteractions(blocks);
        verifyNoInteractions(batches, clock, notes, recipientBlocks, statusBlocks, counts);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    private MockHttpServletRequestBuilder authenticatedNotifications(String role) {
        return get(PATH).header("Authorization", "Bearer " + F3TestInputs.token(7, role));
    }

    private static ContactMember active(long id) { return new ContactMember(id, "본인", true, false); }
}
