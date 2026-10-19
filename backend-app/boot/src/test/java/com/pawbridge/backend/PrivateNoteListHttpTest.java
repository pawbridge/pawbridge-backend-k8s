package com.pawbridge.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pawbridge.backend.boot.configuration.ReadSecurityConfiguration;
import com.pawbridge.backend.community.internal.note.application.ListNoteBlocks;
import com.pawbridge.backend.community.internal.note.application.ListPrivateNotes;
import com.pawbridge.backend.community.internal.note.application.NoteBlockListReader;
import com.pawbridge.backend.community.internal.note.application.NoteBlockReader;
import com.pawbridge.backend.community.internal.note.application.NoteNotificationReader;
import com.pawbridge.backend.community.internal.note.application.PrivateNoteListReader;
import com.pawbridge.backend.community.internal.note.application.PrivateNoteReader;
import com.pawbridge.backend.community.internal.note.application.PrivateNoteReader.StoredNote;
import com.pawbridge.backend.community.internal.note.application.ReadNoteNotifications;
import com.pawbridge.backend.community.internal.note.application.ReadPrivateNote;
import com.pawbridge.backend.community.internal.note.web.NoteBlockListController;
import com.pawbridge.backend.community.internal.note.web.NoteBlockListExceptionHandler;
import com.pawbridge.backend.community.internal.note.web.NoteNotificationsController;
import com.pawbridge.backend.community.internal.note.web.NoteNotificationsExceptionHandler;
import com.pawbridge.backend.community.internal.note.web.PrivateNoteListController;
import com.pawbridge.backend.community.internal.note.web.PrivateNoteListExceptionHandler;
import com.pawbridge.backend.community.internal.note.web.PrivateNoteReadController;
import com.pawbridge.backend.community.internal.note.web.PrivateNoteReadExceptionHandler;
import com.pawbridge.backend.user.api.ContactMember;
import com.pawbridge.backend.user.api.ContactMemberQuery;
import com.pawbridge.backend.user.api.ContactMemberUnavailableException;
import com.pawbridge.backend.user.api.ContactMembersQuery;
import com.pawbridge.backend.user.internal.statistics.application.TotalUserCountQuery;
import com.pawbridge.backend.user.internal.statistics.web.TotalUserCountController;
import com.pawbridge.backend.user.internal.statistics.web.TotalUserCountExceptionHandler;
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

@WebMvcTest(controllers = PrivateNoteListController.class, excludeAutoConfiguration = org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration.class)
@ContextConfiguration(classes = {PrivateNoteListController.class, PrivateNoteListExceptionHandler.class, ListPrivateNotes.class,
        PrivateNoteReadController.class, PrivateNoteReadExceptionHandler.class, ReadPrivateNote.class,
        NoteNotificationsController.class, NoteNotificationsExceptionHandler.class, ReadNoteNotifications.class,
        NoteBlockListController.class, NoteBlockListExceptionHandler.class, ListNoteBlocks.class,
        TotalUserCountController.class, TotalUserCountExceptionHandler.class,
        ReadSecurityConfiguration.class, F3TestInputs.SecurityInputs.class})
class PrivateNoteListHttpTest {
    private static final String PATH = "/api/v1/notes";
    private static final UUID FIRST = new UUID(23L, 1L), SECOND = new UUID(23L, 2L), THIRD = new UUID(23L, 3L);
    private static final Instant NOW = Instant.parse("2031-01-01T00:00:00.999999Z");
    private static final Instant CREATED = Instant.parse("2030-12-31T15:00:00.123456Z");
    private static final String BODY = "  원문\n<태그>  ";
    private static final String SECURITY_CACHE = "no-cache, no-store, max-age=0, must-revalidate";

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @MockitoBean ContactMemberQuery members;
    @MockitoBean ContactMembersQuery batches;
    @MockitoBean PrivateNoteListReader notes;
    @MockitoBean NoteBlockReader blocks;
    @MockitoBean(name = "readClock") Clock clock;
    @MockitoBean PrivateNoteReader details;
    @MockitoBean NoteNotificationReader notifications;
    @MockitoBean NoteBlockListReader blockLists;
    @MockitoBean TotalUserCountQuery counts;

    @ParameterizedTest
    @ValueSource(strings = {"INBOX", "SENT"})
    void given__default_inbox_or_explicit_sent_favorites_and_spoofed_identity__when__listing__then__preserve_exact_five_and_ten_fields_with_raw_values(String box) throws Exception {
        boolean sent = box.equals("SENT");
        int page = sent ? 1 : 0;
        when(members.get(7)).thenReturn(active(7));
        when(clock.instant()).thenReturn(NOW);
        when(notes.count(7, box, sent, NOW)).thenReturn(11L);
        var first = row(FIRST, sent ? 7L : Long.MAX_VALUE, sent ? Long.MAX_VALUE : 7L,
                box, sent ? CREATED.plusNanos(1000) : null, true, "POST", Long.MAX_VALUE);
        var second = row(SECOND, Long.MAX_VALUE, 7L, "INBOX", CREATED.plusNanos(1000), false, "REPORT", 8L);
        var third = row(THIRD, null, 7L, "INBOX", null, false, null, null);
        when(notes.read(7, box, sent, page, NOW)).thenReturn(sent ? List.of(first) : List.of(first, second, third));
        when(batches.getAll(List.of(Long.MAX_VALUE))).thenReturn(List.of(new ContactMember(Long.MAX_VALUE, "  ", true, false)));
        if (!sent) when(blocks.blocked(7, Long.MAX_VALUE)).thenReturn(false, true);
        var request = authenticated(PATH).header("X-User-Id", "999").header("X-User-Role", "ROLE_ADMIN")
                .param("userId", "999").contentType(MediaType.APPLICATION_JSON).content("{\"userId\":999,\"page\":9}");
        if (sent) request.param("box", "SENT").param("favorites", "true").param("page", "1");
        var expected = page(11, 2, page);
        var content = ((ObjectNode) expected.get("data")).withArray("content");
        content.add(view(first, Long.MAX_VALUE, "  ", !sent, "/community/" + Long.MAX_VALUE));
        if (!sent) {
            content.add(view(second, Long.MAX_VALUE, "  ", false, "/reports/8"));
            content.add(view(third, null, "탈퇴한 회원", false, null));
        }

        var response = mvc.perform(request).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store")).andExpect(header().doesNotExist("Set-Cookie"))
                .andExpect(content().json(expected.toString(), JsonCompareMode.STRICT)).andReturn().getResponse();
        var data = mapper.readTree(response.getContentAsString()).path("data");
        assertThat(data.size()).isEqualTo(5);
        for (var note : data.path("content")) assertThat(note.size()).isEqualTo(10);
        assertThat(data.path("content").get(0).path("counterpartId").isIntegralNumber()).isTrue();
        assertThat(data.path("content").get(0).path("counterpartId").longValue()).isEqualTo(Long.MAX_VALUE);
        var order = inOrder(members, clock, notes, batches, blocks);
        order.verify(members).get(7); order.verify(clock).instant();
        order.verify(notes).count(7, box, sent, NOW); order.verify(notes).read(7, box, sent, page, NOW);
        order.verify(batches).getAll(List.of(Long.MAX_VALUE));
        if (!sent) order.verify(blocks, times(2)).blocked(7, Long.MAX_VALUE);
        verifyNoMoreInteractions(members, clock, notes, batches, blocks);
        verifyNoInteractions(details, notifications, blockLists, counts);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void given__zero_count_and_maximum_requested_page__when__listing__then__still_read_and_return_empty_page_without_batch_or_block() throws Exception {
        when(members.get(7)).thenReturn(active(7)); when(clock.instant()).thenReturn(NOW);
        when(notes.count(7, "INBOX", false, NOW)).thenReturn(0L);
        when(notes.read(7, "INBOX", false, 10000, NOW)).thenReturn(List.of());

        mvc.perform(authenticated(PATH).param("page", "10000")).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store")).andExpect(header().doesNotExist("Set-Cookie"))
                .andExpect(content().json(page(0, 0, 10000).toString(), JsonCompareMode.STRICT));
        var order = inOrder(members, clock, notes);
        order.verify(members).get(7); order.verify(clock).instant(); order.verify(notes).count(7, "INBOX", false, NOW);
        order.verify(notes).read(7, "INBOX", false, 10000, NOW);
        verifyNoMoreInteractions(members, clock, notes);
        verifyNoInteractions(batches, blocks, details, notifications, blockLists, counts);
    }

    @ParameterizedTest
    @MethodSource("rejectedAuthorizations")
    void given__missing_or_invalid_jwt_with_invalid_queries__when__listing__then__authenticate_before_binding_business_and_all_dependencies(String authorization, String message) throws Exception {
        var request = get(PATH).param("favorites", "not-a-boolean").param("page", "-1").param("box", "WRONG").header("X-User-Id", "7");
        if (authorization != null) request.header("Authorization", authorization);
        mvc.perform(request).andExpect(status().isUnauthorized())
                .andExpect(header().string("Cache-Control", SECURITY_CACHE)).andExpect(header().doesNotExist("Set-Cookie"))
                .andExpect(content().json(mapper.createObjectNode().put("code", 401).put("message", message).putNull("data").toString(), JsonCompareMode.STRICT));
        noDependencies();
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    static Stream<Arguments> rejectedAuthorizations() {
        return Stream.of(Arguments.of(null, "인증 토큰이 필요합니다."), Arguments.of("Bearer invalid", "유효하지 않거나 만료된 토큰입니다."));
    }

    @ParameterizedTest
    @MethodSource("businessRejections")
    void given__invalid_page_and_box__when__validating__then__page_precedes_box_and_both_precede_member_clock_or_sql(String page, String box, String message) throws Exception {
        mvc.perform(authenticated(PATH).param("page", page).param("box", box)).andExpect(status().isBadRequest())
                .andExpect(header().string("Cache-Control", "no-store")).andExpect(header().doesNotExist("Set-Cookie"))
                .andExpect(content().json(mapper.writeValueAsString(Map.of("message", message)), JsonCompareMode.STRICT));
        noDependencies();
    }

    static Stream<Arguments> businessRejections() {
        return Stream.of(Arguments.of("-1", "WRONG", "페이지 범위를 확인해 주세요."), Arguments.of("0", "inbox", "쪽지함을 확인해 주세요."));
    }

    @ParameterizedTest
    @MethodSource("conversionRejections")
    void given__unconvertible_favorites_or_page_with_invalid_business_queries__when__binding__then__return_conversion_400_before_application_validation(String favorites, String page) throws Exception {
        mvc.perform(authenticated(PATH).param("favorites", favorites).param("page", page).param("box", "WRONG"))
                .andExpect(status().isBadRequest()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().doesNotExist("Set-Cookie"))
                .andExpect(content().json("{\"message\":\"요청 형식과 로그인 정보를 확인해 주세요.\"}", JsonCompareMode.STRICT));
        noDependencies();
    }

    static Stream<Arguments> conversionRejections() {
        return Stream.of(Arguments.of("not-a-boolean", "-1"), Arguments.of("false", "not-an-int"));
    }

    @Test
    void given__pending_current_member_with_valid_queries__when__listing__then__reject_before_clock_count_list_batch_or_block() throws Exception {
        when(members.get(7)).thenReturn(new ContactMember(7L, "탈퇴한 회원", false, true));
        mvc.perform(authenticated(PATH)).andExpect(status().isForbidden())
                .andExpect(header().string("Cache-Control", "no-store")).andExpect(header().doesNotExist("Set-Cookie"))
                .andExpect(content().json("{\"message\":\"이용할 수 없는 회원입니다.\"}", JsonCompareMode.STRICT));
        verify(members).get(7); verifyNoMoreInteractions(members);
        verifyNoInteractions(clock, notes, batches, blocks, details, notifications, blockLists, counts);
    }

    @ParameterizedTest
    @MethodSource("dependencyFailures")
    void given__member_clock_count_list_batch_or_block_failure__when__listing__then__preserve_source_specific_503_and_stop_later_dependencies(String phase, RuntimeException failure, String message) throws Exception {
        if (phase.equals("CURRENT")) when(members.get(7)).thenThrow(failure);
        else {
            when(members.get(7)).thenReturn(active(7));
            if (phase.equals("CLOCK")) when(clock.instant()).thenThrow(failure);
            else {
                when(clock.instant()).thenReturn(NOW);
                if (phase.equals("COUNT")) when(notes.count(7, "INBOX", false, NOW)).thenThrow(failure);
                else {
                    when(notes.count(7, "INBOX", false, NOW)).thenReturn(1L);
                    if (phase.equals("BATCH") || phase.equals("BLOCK")) {
                        when(notes.read(7, "INBOX", false, 0, NOW)).thenReturn(List.of(row(FIRST, 8L, 7L, "INBOX", null, false, null, null)));
                        if (phase.equals("BATCH")) when(batches.getAll(List.of(8L))).thenThrow(failure);
                        else { when(batches.getAll(List.of(8L))).thenReturn(List.of(active(8))); when(blocks.blocked(7, 8)).thenThrow(failure); }
                    } else when(notes.read(7, "INBOX", false, 0, NOW)).thenThrow(failure);
                }
            }
        }

        mvc.perform(authenticated(PATH)).andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Cache-Control", "no-store")).andExpect(header().doesNotExist("Set-Cookie"))
                .andExpect(content().json(mapper.writeValueAsString(Map.of("message", message)), JsonCompareMode.STRICT));
        var order = inOrder(members, clock, notes, batches, blocks);
        order.verify(members).get(7);
        if (!phase.equals("CURRENT")) order.verify(clock).instant();
        if (!phase.equals("CURRENT") && !phase.equals("CLOCK")) order.verify(notes).count(7, "INBOX", false, NOW);
        if (!List.of("CURRENT", "CLOCK", "COUNT").contains(phase)) order.verify(notes).read(7, "INBOX", false, 0, NOW);
        if (phase.equals("BATCH") || phase.equals("BLOCK")) order.verify(batches).getAll(List.of(8L));
        if (phase.equals("BLOCK")) order.verify(blocks).blocked(7, 8);
        verifyNoMoreInteractions(members, clock, notes, batches, blocks);
        verifyNoInteractions(details, notifications, blockLists, counts);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    static Stream<Arguments> dependencyFailures() {
        String member = "회원 정보를 확인할 수 없습니다. 잠시 후 다시 시도해 주세요.";
        String storage = "쪽지 저장소를 사용할 수 없습니다. 잠시 후 다시 시도해 주세요.";
        String generic = "쪽지를 처리하지 못했습니다. 잠시 후 다시 시도해 주세요.";
        return Stream.of(Arguments.of("CURRENT", new ContactMemberUnavailableException(new IllegalStateException("synthetic-current")), member),
                Arguments.of("BATCH", new ContactMemberUnavailableException(new TransactionSystemException("synthetic-user-commit")), member),
                Arguments.of("COUNT", new DataAccessResourceFailureException("synthetic-count"), storage),
                Arguments.of("BLOCK", new DataAccessResourceFailureException("synthetic-block"), storage),
                Arguments.of("BEGIN", new CannotCreateTransactionException("synthetic-list-begin"), storage),
                Arguments.of("COMMIT", new TransactionSystemException("synthetic-list-commit"), storage),
                Arguments.of("TIMEOUT", new TransactionTimedOutException("synthetic-list-timeout"), storage),
                Arguments.of("USAGE", new IllegalTransactionStateException("synthetic-list-usage"), generic),
                Arguments.of("CLOCK", new IllegalStateException("synthetic-clock"), generic),
                Arguments.of("RUNTIME", new IllegalStateException("synthetic-list-body"), generic));
    }

    @ParameterizedTest
    @ValueSource(strings = {"MISSING", "INVALID", "VALID"})
    void given__stream_with_no_bad_or_valid_jwt__when__requesting__then__retain_default_403_without_list_or_sse_work(String token) throws Exception {
        var request = get(PATH + "/stream").accept(MediaType.TEXT_EVENT_STREAM);
        if (!token.equals("MISSING")) request.header("Authorization", token.equals("INVALID") ? "Bearer invalid" : "Bearer " + F3TestInputs.token(7, "ROLE_USER"));
        mvc.perform(request).andExpect(status().isForbidden()).andExpect(header().string("Cache-Control", SECURITY_CACHE))
                .andExpect(header().doesNotExist("Set-Cookie"))
                .andExpect(content().json("{\"code\":403,\"message\":\"접근할 수 없습니다.\",\"data\":null}", JsonCompareMode.STRICT));
        noDependencies();
    }

    @Test
    void given__existing_static_and_detail_routes__when__reading__then__keep_their_success_error_and_scoped_advice_outside_list() throws Exception {
        when(members.get(7)).thenReturn(active(7)); when(clock.instant()).thenReturn(NOW);
        when(notifications.read(7, null, NOW)).thenReturn(List.of()); when(notifications.unread(7, NOW)).thenReturn(0L);
        when(details.find(7, FIRST, NOW)).thenReturn(Optional.empty());
        mvc.perform(authenticated(PATH + "/notifications")).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store")).andExpect(header().doesNotExist("Set-Cookie"))
                .andExpect(content().json("{\"code\":200,\"message\":\"요청이 성공적으로 처리되었습니다.\",\"data\":{\"content\":[],\"nextCursor\":null,\"unreadCount\":0}}", JsonCompareMode.STRICT));
        mvc.perform(authenticated(PATH + "/" + FIRST)).andExpect(status().isNotFound())
                .andExpect(header().string("Cache-Control", "no-store")).andExpect(header().doesNotExist("Set-Cookie"))
                .andExpect(content().json("{\"message\":\"쪽지를 찾을 수 없습니다.\"}", JsonCompareMode.STRICT));
        mvc.perform(authenticated(PATH + "/blocks").param("page", "-1")).andExpect(status().isBadRequest())
                .andExpect(header().string("Cache-Control", "no-store")).andExpect(header().doesNotExist("Set-Cookie"))
                .andExpect(content().json("{\"message\":\"페이지 범위를 확인해 주세요.\"}", JsonCompareMode.STRICT));
        verify(members, times(2)).get(7); verify(clock, times(2)).instant();
        verify(notifications).read(7, null, NOW); verify(notifications).unread(7, NOW); verify(details).find(7, FIRST, NOW);
        verifyNoMoreInteractions(members, clock, notifications, details);
        verifyNoInteractions(notes, batches, blocks, blockLists, counts);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"HEAD /api/v1/notes", "OPTIONS /api/v1/notes", "POST /api/v1/notes", "GET /api/notes", "GET /api/v1/notes/"})
    void given__unexposed_root_alias_trailing_slash_or_method__when__requesting__then__deny_without_list_or_writer_work(String wire) throws Exception {
        String[] parts = wire.split(" ", 2);
        mvc.perform(request(HttpMethod.valueOf(parts[0]), parts[1]).header("Authorization", "Bearer " + F3TestInputs.token(7, "ROLE_USER")))
                .andExpect(status().isForbidden()).andExpect(header().string("Cache-Control", SECURITY_CACHE))
                .andExpect(header().doesNotExist("Set-Cookie"));
        noDependencies();
    }

    @Test
    void given__existing_admin_count_permission_and_runtime_error__when__requesting__then__keep_admin_403_and_common_500_outside_list_advice() throws Exception {
        when(counts.count()).thenThrow(new IllegalStateException("synthetic-admin-count"));
        mvc.perform(authenticated("/api/v1/admin/users/stats/total-users")).andExpect(status().isForbidden())
                .andExpect(header().string("Cache-Control", SECURITY_CACHE)).andExpect(header().doesNotExist("Set-Cookie"))
                .andExpect(content().json("{\"code\":403,\"message\":\"관리자 권한이 필요합니다.\",\"data\":null}", JsonCompareMode.STRICT));
        mvc.perform(get("/api/v1/admin/users/stats/total-users").header("Authorization", "Bearer " + F3TestInputs.token(7, "ROLE_ADMIN")))
                .andExpect(status().isInternalServerError()).andExpect(header().string("Cache-Control", SECURITY_CACHE))
                .andExpect(header().doesNotExist("Set-Cookie"))
                .andExpect(content().json("{\"code\":500,\"message\":\"서버 에러!\",\"data\":null}", JsonCompareMode.STRICT));
        verify(counts).count(); verifyNoMoreInteractions(counts);
        verifyNoInteractions(members, batches, clock, notes, blocks, details, notifications, blockLists);
    }

    private ObjectNode page(long total, int pages, int number) {
        var result = mapper.createObjectNode().put("code", 200).put("message", "요청이 성공적으로 처리되었습니다.");
        var data = result.putObject("data"); data.putArray("content");
        data.put("totalElements", total).put("totalPages", pages).put("number", number).put("size", 10);
        return result;
    }

    private ObjectNode view(StoredNote row, Long counterpart, String nickname, boolean reply, String href) {
        var value = mapper.createObjectNode().put("noteId", row.noteId().toString()).put("body", BODY).put("direction", row.direction())
                .put("createdAt", CREATED.toString()).put("readAt", row.readAt() == null ? null : row.readAt().toString())
                .put("favorite", row.favorite()).put("counterpartNickname", nickname).put("canReply", reply).put("contextHref", href);
        if (counterpart == null) value.putNull("counterpartId"); else value.put("counterpartId", counterpart);
        return value;
    }

    private static StoredNote row(UUID id, Long sender, Long recipient, String box, Instant readAt, boolean favorite, String context, Long contextId) {
        return new StoredNote(id, sender, recipient, BODY, context, contextId, CREATED, box, readAt, favorite);
    }

    private MockHttpServletRequestBuilder authenticated(String path) {
        return get(path).header("Authorization", "Bearer " + F3TestInputs.token(7, "ROLE_USER"));
    }

    private void noDependencies() { verifyNoInteractions(members, batches, notes, blocks, clock, details, notifications, blockLists, counts); }
    private static ContactMember active(long id) { return new ContactMember(id, "회원", true, false); }
}
