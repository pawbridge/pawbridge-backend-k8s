package com.pawbridge.backend

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import com.pawbridge.backend.boot.configuration.ReadSecurityConfiguration
import com.pawbridge.backend.community.internal.note.application.ListNoteBlocks
import com.pawbridge.backend.community.internal.note.application.ListPrivateNotes
import com.pawbridge.backend.community.internal.note.application.NoteBlockListReader
import com.pawbridge.backend.community.internal.note.application.NoteBlockReader
import com.pawbridge.backend.community.internal.note.application.NoteNotificationReader
import com.pawbridge.backend.community.internal.note.application.PrivateNoteListReader
import com.pawbridge.backend.community.internal.note.application.PrivateNoteReader
import com.pawbridge.backend.community.internal.note.application.PrivateNoteReader.StoredNote
import com.pawbridge.backend.community.internal.note.application.ReadNoteNotifications
import com.pawbridge.backend.community.internal.note.application.ReadPrivateNote
import com.pawbridge.backend.community.internal.note.web.NoteBlockListController
import com.pawbridge.backend.community.internal.note.web.NoteBlockListExceptionHandler
import com.pawbridge.backend.community.internal.note.web.NoteNotificationsController
import com.pawbridge.backend.community.internal.note.web.NoteNotificationsExceptionHandler
import com.pawbridge.backend.community.internal.note.web.PrivateNoteListController
import com.pawbridge.backend.community.internal.note.web.PrivateNoteListExceptionHandler
import com.pawbridge.backend.community.internal.note.web.PrivateNoteReadController
import com.pawbridge.backend.community.internal.note.web.PrivateNoteReadExceptionHandler
import com.pawbridge.backend.user.api.ContactMember
import com.pawbridge.backend.user.api.ContactMemberQuery
import com.pawbridge.backend.user.api.ContactMemberUnavailableException
import com.pawbridge.backend.user.api.ContactMembersQuery
import com.pawbridge.backend.user.internal.statistics.application.TotalUserCountQuery
import com.pawbridge.backend.user.internal.statistics.web.TotalUserCountController
import com.pawbridge.backend.user.internal.statistics.web.TotalUserCountExceptionHandler
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import org.junit.jupiter.params.provider.ValueSource
import org.mockito.Mockito.inOrder
import org.mockito.Mockito.`when` as whenever
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.verifyNoMoreInteractions
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.dao.DataAccessResourceFailureException
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.test.context.ContextConfiguration
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.json.JsonCompareMode
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.transaction.CannotCreateTransactionException
import org.springframework.transaction.IllegalTransactionStateException
import org.springframework.transaction.TransactionSystemException
import org.springframework.transaction.TransactionTimedOutException
import java.time.Clock
import java.time.Instant
import java.util.Optional
import java.util.UUID
import java.util.stream.Stream

@WebMvcTest(
    controllers = [PrivateNoteListController::class],
    excludeAutoConfiguration = [UserDetailsServiceAutoConfiguration::class],
)
@ContextConfiguration(
    classes = [
        PrivateNoteListController::class,
        PrivateNoteListExceptionHandler::class,
        ListPrivateNotes::class,
        PrivateNoteReadController::class,
        PrivateNoteReadExceptionHandler::class,
        ReadPrivateNote::class,
        NoteNotificationsController::class,
        NoteNotificationsExceptionHandler::class,
        ReadNoteNotifications::class,
        NoteBlockListController::class,
        NoteBlockListExceptionHandler::class,
        ListNoteBlocks::class,
        TotalUserCountController::class,
        TotalUserCountExceptionHandler::class,
        ReadSecurityConfiguration::class,
        F3TestInputs.SecurityInputs::class,
    ],
)
class PrivateNoteListHttpTest {
    @Autowired
    lateinit var mvc: MockMvc

    @Autowired
    lateinit var mapper: ObjectMapper

    @MockitoBean
    lateinit var members: ContactMemberQuery

    @MockitoBean
    lateinit var batches: ContactMembersQuery

    @MockitoBean
    lateinit var notes: PrivateNoteListReader

    @MockitoBean
    lateinit var blocks: NoteBlockReader

    @MockitoBean(name = "readClock")
    lateinit var clock: Clock

    @MockitoBean
    lateinit var details: PrivateNoteReader

    @MockitoBean
    lateinit var notifications: NoteNotificationReader

    @MockitoBean
    lateinit var blockLists: NoteBlockListReader

    @MockitoBean
    lateinit var counts: TotalUserCountQuery

    @ParameterizedTest
    @ValueSource(strings = ["INBOX", "SENT"])
    fun given__default_inbox_or_explicit_sent_favorites_and_spoofed_identity__when__listing__then__preserve_exact_five_and_ten_fields_with_raw_values(
        box: String,
    ) {
        val sent = box == "SENT"
        val page = if (sent) 1 else 0
        whenever(members.get(7)).thenReturn(active(7))
        whenever(clock.instant()).thenReturn(NOW)
        whenever(notes.count(7, box, sent, NOW)).thenReturn(11L)
        val first = row(
            FIRST,
            if (sent) 7L else Long.MAX_VALUE,
            if (sent) Long.MAX_VALUE else 7L,
            box,
            if (sent) CREATED.plusNanos(1000) else null,
            true,
            "POST",
            Long.MAX_VALUE,
        )
        val second = row(SECOND, Long.MAX_VALUE, 7L, "INBOX", CREATED.plusNanos(1000), false, "REPORT", 8L)
        val third = row(THIRD, null, 7L, "INBOX", null, false, null, null)
        whenever(notes.read(7, box, sent, page, NOW)).thenReturn(if (sent) listOf(first) else listOf(first, second, third))
        whenever(batches.getAll(listOf(Long.MAX_VALUE))).thenReturn(listOf(ContactMember(Long.MAX_VALUE, "  ", true, false)))
        if (!sent) {
            whenever(blocks.blocked(7, Long.MAX_VALUE)).thenReturn(false, true)
        }
        val request = authenticated(PATH)
            .header("X-User-Id", "999")
            .header("X-User-Role", "ROLE_ADMIN")
            .param("userId", "999")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"userId\":999,\"page\":9}")
        if (sent) {
            request.param("box", "SENT").param("favorites", "true").param("page", "1")
        }
        val expected = page(11, 2, page)
        val content = (expected.get("data") as ObjectNode).withArray("content")
        content.add(view(first, Long.MAX_VALUE, "  ", !sent, "/community/" + Long.MAX_VALUE))
        if (!sent) {
            content.add(view(second, Long.MAX_VALUE, "  ", false, "/reports/8"))
            content.add(view(third, null, "탈퇴한 회원", false, null))
        }

        val response = mvc.perform(request)
            .andExpect(status().isOk())
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(header().doesNotExist("Set-Cookie"))
            .andExpect(content().json(expected.toString(), JsonCompareMode.STRICT))
            .andReturn().response
        val data = mapper.readTree(response.contentAsString).path("data")
        assertThat(data.size()).isEqualTo(5)
        for (note in data.path("content")) {
            assertThat(note.size()).isEqualTo(10)
        }
        assertThat(data.path("content").get(0).path("counterpartId").isIntegralNumber()).isTrue()
        assertThat(data.path("content").get(0).path("counterpartId").longValue()).isEqualTo(Long.MAX_VALUE)
        val order = inOrder(members, clock, notes, batches, blocks)
        order.verify(members).get(7)
        order.verify(clock).instant()
        order.verify(notes).count(7, box, sent, NOW)
        order.verify(notes).read(7, box, sent, page, NOW)
        order.verify(batches).getAll(listOf(Long.MAX_VALUE))
        if (!sent) {
            order.verify(blocks, times(2)).blocked(7, Long.MAX_VALUE)
        }
        verifyNoMoreInteractions(members, clock, notes, batches, blocks)
        verifyNoInteractions(details, notifications, blockLists, counts)
        assertThat(SecurityContextHolder.getContext().authentication).isNull()
    }

    @Test
    fun given__zero_count_and_maximum_requested_page__when__listing__then__still_read_and_return_empty_page_without_batch_or_block() {
        whenever(members.get(7)).thenReturn(active(7))
        whenever(clock.instant()).thenReturn(NOW)
        whenever(notes.count(7, "INBOX", false, NOW)).thenReturn(0L)
        whenever(notes.read(7, "INBOX", false, 10000, NOW)).thenReturn(listOf())

        mvc.perform(authenticated(PATH).param("page", "10000"))
            .andExpect(status().isOk())
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(header().doesNotExist("Set-Cookie"))
            .andExpect(content().json(page(0, 0, 10000).toString(), JsonCompareMode.STRICT))
        val order = inOrder(members, clock, notes)
        order.verify(members).get(7)
        order.verify(clock).instant()
        order.verify(notes).count(7, "INBOX", false, NOW)
        order.verify(notes).read(7, "INBOX", false, 10000, NOW)
        verifyNoMoreInteractions(members, clock, notes)
        verifyNoInteractions(batches, blocks, details, notifications, blockLists, counts)
    }

    @ParameterizedTest
    @MethodSource("rejectedAuthorizations")
    fun given__missing_or_invalid_jwt_with_invalid_queries__when__listing__then__authenticate_before_binding_business_and_all_dependencies(
        authorization: String?,
        message: String,
    ) {
        val request = get(PATH)
            .param("favorites", "not-a-boolean")
            .param("page", "-1")
            .param("box", "WRONG")
            .header("X-User-Id", "7")
        if (authorization != null) {
            request.header("Authorization", authorization)
        }
        mvc.perform(request)
            .andExpect(status().isUnauthorized())
            .andExpect(header().string("Cache-Control", SECURITY_CACHE))
            .andExpect(header().doesNotExist("Set-Cookie"))
            .andExpect(
                content().json(
                    mapper.createObjectNode().put("code", 401).put("message", message).putNull("data").toString(),
                    JsonCompareMode.STRICT,
                ),
            )
        noDependencies()
        assertThat(SecurityContextHolder.getContext().authentication).isNull()
    }

    @ParameterizedTest
    @MethodSource("businessRejections")
    fun given__invalid_page_and_box__when__validating__then__page_precedes_box_and_both_precede_member_clock_or_sql(
        page: String,
        box: String,
        message: String,
    ) {
        mvc.perform(authenticated(PATH).param("page", page).param("box", box))
            .andExpect(status().isBadRequest())
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(header().doesNotExist("Set-Cookie"))
            .andExpect(content().json(mapper.writeValueAsString(mapOf("message" to message)), JsonCompareMode.STRICT))
        noDependencies()
    }

    @ParameterizedTest
    @MethodSource("conversionRejections")
    fun given__unconvertible_favorites_or_page_with_invalid_business_queries__when__binding__then__return_conversion_400_before_application_validation(
        favorites: String,
        page: String,
    ) {
        mvc.perform(authenticated(PATH).param("favorites", favorites).param("page", page).param("box", "WRONG"))
            .andExpect(status().isBadRequest())
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(header().doesNotExist("Set-Cookie"))
            .andExpect(content().json("{\"message\":\"요청 형식과 로그인 정보를 확인해 주세요.\"}", JsonCompareMode.STRICT))
        noDependencies()
    }

    @Test
    fun given__pending_current_member_with_valid_queries__when__listing__then__reject_before_clock_count_list_batch_or_block() {
        whenever(members.get(7)).thenReturn(ContactMember(7L, "탈퇴한 회원", false, true))
        mvc.perform(authenticated(PATH))
            .andExpect(status().isForbidden())
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(header().doesNotExist("Set-Cookie"))
            .andExpect(content().json("{\"message\":\"이용할 수 없는 회원입니다.\"}", JsonCompareMode.STRICT))
        verify(members).get(7)
        verifyNoMoreInteractions(members)
        verifyNoInteractions(clock, notes, batches, blocks, details, notifications, blockLists, counts)
    }

    @ParameterizedTest
    @MethodSource("dependencyFailures")
    fun given__member_clock_count_list_batch_or_block_failure__when__listing__then__preserve_source_specific_503_and_stop_later_dependencies(
        phase: String,
        failure: RuntimeException,
        message: String,
    ) {
        if (phase == "CURRENT") {
            whenever(members.get(7)).thenThrow(failure)
        } else {
            whenever(members.get(7)).thenReturn(active(7))
            if (phase == "CLOCK") {
                whenever(clock.instant()).thenThrow(failure)
            } else {
                whenever(clock.instant()).thenReturn(NOW)
                if (phase == "COUNT") {
                    whenever(notes.count(7, "INBOX", false, NOW)).thenThrow(failure)
                } else {
                    whenever(notes.count(7, "INBOX", false, NOW)).thenReturn(1L)
                    if (phase == "BATCH" || phase == "BLOCK") {
                        whenever(notes.read(7, "INBOX", false, 0, NOW))
                            .thenReturn(listOf(row(FIRST, 8L, 7L, "INBOX", null, false, null, null)))
                        if (phase == "BATCH") {
                            whenever(batches.getAll(listOf(8L))).thenThrow(failure)
                        } else {
                            whenever(batches.getAll(listOf(8L))).thenReturn(listOf(active(8)))
                            whenever(blocks.blocked(7, 8)).thenThrow(failure)
                        }
                    } else {
                        whenever(notes.read(7, "INBOX", false, 0, NOW)).thenThrow(failure)
                    }
                }
            }
        }

        mvc.perform(authenticated(PATH))
            .andExpect(status().isServiceUnavailable())
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(header().doesNotExist("Set-Cookie"))
            .andExpect(content().json(mapper.writeValueAsString(mapOf("message" to message)), JsonCompareMode.STRICT))
        val order = inOrder(members, clock, notes, batches, blocks)
        order.verify(members).get(7)
        if (phase != "CURRENT") {
            order.verify(clock).instant()
        }
        if (phase != "CURRENT" && phase != "CLOCK") {
            order.verify(notes).count(7, "INBOX", false, NOW)
        }
        if (!listOf("CURRENT", "CLOCK", "COUNT").contains(phase)) {
            order.verify(notes).read(7, "INBOX", false, 0, NOW)
        }
        if (phase == "BATCH" || phase == "BLOCK") {
            order.verify(batches).getAll(listOf(8L))
        }
        if (phase == "BLOCK") {
            order.verify(blocks).blocked(7, 8)
        }
        verifyNoMoreInteractions(members, clock, notes, batches, blocks)
        verifyNoInteractions(details, notifications, blockLists, counts)
        assertThat(SecurityContextHolder.getContext().authentication).isNull()
    }

    @ParameterizedTest
    @ValueSource(strings = ["MISSING", "INVALID", "VALID"])
    fun given__stream_extension_with_no_bad_or_valid_jwt__when__requesting__then__retain_default_403_without_list_or_sse_work(
        token: String,
    ) {
        val request = get("$PATH/stream/extra").accept(MediaType.TEXT_EVENT_STREAM)
        if (token != "MISSING") {
            request.header(
                "Authorization",
                if (token == "INVALID") "Bearer invalid" else "Bearer " + F3TestInputs.token(7, "ROLE_USER"),
            )
        }
        mvc.perform(request)
            .andExpect(status().isForbidden())
            .andExpect(header().string("Cache-Control", SECURITY_CACHE))
            .andExpect(header().doesNotExist("Set-Cookie"))
            .andExpect(
                content().json("{\"code\":403,\"message\":\"접근할 수 없습니다.\",\"data\":null}", JsonCompareMode.STRICT),
            )
        noDependencies()
    }

    @Test
    fun given__existing_static_and_detail_routes__when__reading__then__keep_their_success_error_and_scoped_advice_outside_list() {
        whenever(members.get(7)).thenReturn(active(7))
        whenever(clock.instant()).thenReturn(NOW)
        whenever(notifications.read(7, null, NOW)).thenReturn(listOf())
        whenever(notifications.unread(7, NOW)).thenReturn(0L)
        whenever(details.find(7, FIRST, NOW)).thenReturn(Optional.empty())
        mvc.perform(authenticated("$PATH/notifications"))
            .andExpect(status().isOk())
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(header().doesNotExist("Set-Cookie"))
            .andExpect(
                content().json(
                    "{\"code\":200,\"message\":\"요청이 성공적으로 처리되었습니다.\",\"data\":{\"content\":[],\"nextCursor\":null,\"unreadCount\":0}}",
                    JsonCompareMode.STRICT,
                ),
            )
        mvc.perform(authenticated("$PATH/$FIRST"))
            .andExpect(status().isNotFound())
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(header().doesNotExist("Set-Cookie"))
            .andExpect(content().json("{\"message\":\"쪽지를 찾을 수 없습니다.\"}", JsonCompareMode.STRICT))
        mvc.perform(authenticated("$PATH/blocks").param("page", "-1"))
            .andExpect(status().isBadRequest())
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(header().doesNotExist("Set-Cookie"))
            .andExpect(content().json("{\"message\":\"페이지 범위를 확인해 주세요.\"}", JsonCompareMode.STRICT))
        verify(members, times(2)).get(7)
        verify(clock, times(2)).instant()
        verify(notifications).read(7, null, NOW)
        verify(notifications).unread(7, NOW)
        verify(details).find(7, FIRST, NOW)
        verifyNoMoreInteractions(members, clock, notifications, details)
        verifyNoInteractions(notes, batches, blocks, blockLists, counts)
        assertThat(SecurityContextHolder.getContext().authentication).isNull()
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "HEAD /api/v1/notes", "OPTIONS /api/v1/notes", "POST /api/v1/notes/unexposed/send", "GET /api/notes",
            "GET /api/v1/notes/",
        ],
    )
    fun given__unexposed_root_alias_trailing_slash_or_method__when__requesting__then__deny_without_list_or_writer_work(
        wire: String,
    ) {
        val parts = wire.split(" ", limit = 2)
        mvc.perform(
            request(HttpMethod.valueOf(parts[0]), parts[1])
                .header("Authorization", "Bearer " + F3TestInputs.token(7, "ROLE_USER")),
        )
            .andExpect(status().isForbidden())
            .andExpect(header().string("Cache-Control", SECURITY_CACHE))
            .andExpect(header().doesNotExist("Set-Cookie"))
        noDependencies()
    }

    @Test
    fun given__existing_admin_count_permission_and_runtime_error__when__requesting__then__keep_admin_403_and_common_500_outside_list_advice() {
        whenever(counts.count()).thenThrow(IllegalStateException("synthetic-admin-count"))
        mvc.perform(authenticated("/api/v1/admin/users/stats/total-users"))
            .andExpect(status().isForbidden())
            .andExpect(header().string("Cache-Control", SECURITY_CACHE))
            .andExpect(header().doesNotExist("Set-Cookie"))
            .andExpect(
                content().json("{\"code\":403,\"message\":\"관리자 권한이 필요합니다.\",\"data\":null}", JsonCompareMode.STRICT),
            )
        mvc.perform(
            get("/api/v1/admin/users/stats/total-users")
                .header("Authorization", "Bearer " + F3TestInputs.token(7, "ROLE_ADMIN")),
        )
            .andExpect(status().isInternalServerError())
            .andExpect(header().string("Cache-Control", SECURITY_CACHE))
            .andExpect(header().doesNotExist("Set-Cookie"))
            .andExpect(content().json("{\"code\":500,\"message\":\"서버 에러!\",\"data\":null}", JsonCompareMode.STRICT))
        verify(counts).count()
        verifyNoMoreInteractions(counts)
        verifyNoInteractions(members, batches, clock, notes, blocks, details, notifications, blockLists)
    }

    private fun page(total: Long, pages: Int, number: Int): ObjectNode {
        val result = mapper.createObjectNode().put("code", 200).put("message", "요청이 성공적으로 처리되었습니다.")
        val data = result.putObject("data")
        data.putArray("content")
        data.put("totalElements", total).put("totalPages", pages).put("number", number).put("size", 10)
        return result
    }

    private fun view(row: StoredNote, counterpart: Long?, nickname: String, reply: Boolean, href: String?): ObjectNode {
        val value = mapper.createObjectNode()
            .put("noteId", row.noteId.toString())
            .put("body", BODY)
            .put("direction", row.direction)
            .put("createdAt", CREATED.toString())
            .put("readAt", row.readAt?.toString())
            .put("favorite", row.favorite)
            .put("counterpartNickname", nickname)
            .put("canReply", reply)
            .put("contextHref", href)
        if (counterpart == null) {
            value.putNull("counterpartId")
        } else {
            value.put("counterpartId", counterpart)
        }
        return value
    }

    private fun authenticated(path: String): MockHttpServletRequestBuilder {
        return get(path).header("Authorization", "Bearer " + F3TestInputs.token(7, "ROLE_USER"))
    }

    private fun noDependencies() {
        verifyNoInteractions(members, batches, notes, blocks, clock, details, notifications, blockLists, counts)
    }

    companion object {
        private const val PATH = "/api/v1/notes"
        private val FIRST = UUID(23L, 1L)
        private val SECOND = UUID(23L, 2L)
        private val THIRD = UUID(23L, 3L)
        private val NOW: Instant = Instant.parse("2031-01-01T00:00:00.999999Z")
        private val CREATED: Instant = Instant.parse("2030-12-31T15:00:00.123456Z")
        private const val BODY = "  원문\n<태그>  "
        private const val SECURITY_CACHE = "no-cache, no-store, max-age=0, must-revalidate"

        @JvmStatic
        fun rejectedAuthorizations(): Stream<Arguments> {
            return Stream.of(
                Arguments.of(null, "인증 토큰이 필요합니다."),
                Arguments.of("Bearer invalid", "유효하지 않거나 만료된 토큰입니다."),
            )
        }

        @JvmStatic
        fun businessRejections(): Stream<Arguments> {
            return Stream.of(
                Arguments.of("-1", "WRONG", "페이지 범위를 확인해 주세요."),
                Arguments.of("0", "inbox", "쪽지함을 확인해 주세요."),
            )
        }

        @JvmStatic
        fun conversionRejections(): Stream<Arguments> {
            return Stream.of(Arguments.of("not-a-boolean", "-1"), Arguments.of("false", "not-an-int"))
        }

        @JvmStatic
        fun dependencyFailures(): Stream<Arguments> {
            val member = "회원 정보를 확인할 수 없습니다. 잠시 후 다시 시도해 주세요."
            val storage = "쪽지 저장소를 사용할 수 없습니다. 잠시 후 다시 시도해 주세요."
            val generic = "쪽지를 처리하지 못했습니다. 잠시 후 다시 시도해 주세요."
            return Stream.of(
                Arguments.of(
                    "CURRENT",
                    ContactMemberUnavailableException(IllegalStateException("synthetic-current")),
                    member,
                ),
                Arguments.of(
                    "BATCH",
                    ContactMemberUnavailableException(TransactionSystemException("synthetic-user-commit")),
                    member,
                ),
                Arguments.of("COUNT", DataAccessResourceFailureException("synthetic-count"), storage),
                Arguments.of("BLOCK", DataAccessResourceFailureException("synthetic-block"), storage),
                Arguments.of("BEGIN", CannotCreateTransactionException("synthetic-list-begin"), storage),
                Arguments.of("COMMIT", TransactionSystemException("synthetic-list-commit"), storage),
                Arguments.of("TIMEOUT", TransactionTimedOutException("synthetic-list-timeout"), storage),
                Arguments.of("USAGE", IllegalTransactionStateException("synthetic-list-usage"), generic),
                Arguments.of("CLOCK", IllegalStateException("synthetic-clock"), generic),
                Arguments.of("RUNTIME", IllegalStateException("synthetic-list-body"), generic),
            )
        }

        private fun row(
            id: UUID,
            sender: Long?,
            recipient: Long?,
            box: String,
            readAt: Instant?,
            favorite: Boolean,
            context: String?,
            contextId: Long?,
        ): StoredNote {
            return StoredNote(id, sender, recipient, BODY, context, contextId, CREATED, box, readAt, favorite)
        }

        private fun active(id: Long): ContactMember = ContactMember(id, "회원", true, false)
    }
}
