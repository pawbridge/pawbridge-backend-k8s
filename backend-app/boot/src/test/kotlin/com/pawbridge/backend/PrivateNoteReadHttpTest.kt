package com.pawbridge.backend

import com.fasterxml.jackson.databind.ObjectMapper
import com.pawbridge.backend.boot.configuration.ReadSecurityConfiguration
import com.pawbridge.backend.community.internal.note.application.ListNoteBlocks
import com.pawbridge.backend.community.internal.note.application.NoteBlockListReader
import com.pawbridge.backend.community.internal.note.application.NoteBlockReader
import com.pawbridge.backend.community.internal.note.application.NoteNotificationReader
import com.pawbridge.backend.community.internal.note.application.PrivateNoteReader
import com.pawbridge.backend.community.internal.note.application.ReadNoteNotifications
import com.pawbridge.backend.community.internal.note.application.ReadPrivateNote
import com.pawbridge.backend.community.internal.note.web.NoteBlockListController
import com.pawbridge.backend.community.internal.note.web.NoteBlockListExceptionHandler
import com.pawbridge.backend.community.internal.note.web.NoteNotificationsController
import com.pawbridge.backend.community.internal.note.web.NoteNotificationsExceptionHandler
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
import java.sql.SQLException
import java.time.Clock
import java.time.Instant
import java.util.Optional
import java.util.UUID
import java.util.stream.Stream

@WebMvcTest(
    controllers = [PrivateNoteReadController::class],
    excludeAutoConfiguration = [UserDetailsServiceAutoConfiguration::class],
)
@ContextConfiguration(
    classes = [
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
class PrivateNoteReadHttpTest {
    @Autowired
    lateinit var mvc: MockMvc

    @Autowired
    lateinit var mapper: ObjectMapper

    @MockitoBean
    lateinit var members: ContactMemberQuery

    @MockitoBean
    lateinit var notes: PrivateNoteReader

    @MockitoBean
    lateinit var blocks: NoteBlockReader

    @MockitoBean(name = "readClock")
    lateinit var clock: Clock

    @MockitoBean
    lateinit var batches: ContactMembersQuery

    @MockitoBean
    lateinit var blockLists: NoteBlockListReader

    @MockitoBean
    lateinit var notifications: NoteNotificationReader

    @MockitoBean
    lateinit var counts: TotalUserCountQuery

    @ParameterizedTest
    @MethodSource("directions")
    fun given__owned_inbox_or_sent_note_and_spoofed_identity__when__reading__then__preserve_exact_private_wire_counterpart_and_conditional_reply_lookup(
        direction: String,
        sender: Long,
        recipient: Long,
        readAt: Instant?,
        favorite: Boolean,
        contextType: String,
        contextHref: String,
        canReply: Boolean,
    ) {
        whenever(members.get(7)).thenReturn(active(7))
        whenever(clock.instant()).thenReturn(NOW)
        whenever(notes.find(7, NOTE, NOW)).thenReturn(
            Optional.of(
                PrivateNoteReader.StoredNote(
                    NOTE, sender, recipient, BODY, contextType, 13L, CREATED_AT, direction, readAt, favorite,
                ),
            ),
        )
        whenever(members.get(Long.MAX_VALUE)).thenReturn(ContactMember(Long.MAX_VALUE, "  ", true, false))
        if (canReply) {
            whenever(blocks.blocked(7, Long.MAX_VALUE)).thenReturn(false)
        }

        val response = mvc.perform(
            authenticated(PATH)
                .header("X-User-Id", "999")
                .header("X-User-Role", "ROLE_ADMIN")
                .param("userId", "999")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"userId\":999}"),
        )
            .andExpect(status().isOk())
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(header().doesNotExist("Set-Cookie"))
            .andExpect(
                content().json(
                    expectedNote(direction, readAt, favorite, Long.MAX_VALUE, "  ", canReply, contextHref),
                    JsonCompareMode.STRICT,
                ),
            )
            .andReturn().response

        val counterpart = mapper.readTree(response.contentAsString).path("data").path("counterpartId")
        assertThat(counterpart.isIntegralNumber()).isTrue()
        assertThat(counterpart.longValue()).isEqualTo(Long.MAX_VALUE)
        val order = inOrder(members, clock, notes, blocks)
        order.verify(members).get(7)
        order.verify(clock).instant()
        order.verify(notes).find(7, NOTE, NOW)
        order.verify(members).get(Long.MAX_VALUE)
        if (canReply) {
            order.verify(blocks).blocked(7, Long.MAX_VALUE)
        } else {
            verifyNoInteractions(blocks)
        }
        verifyNoMoreInteractions(members, clock, notes, blocks)
        verifyNoInteractions(batches, blockLists, notifications, counts)
        assertThat(SecurityContextHolder.getContext().authentication).isNull()
    }

    @Test
    fun given__blocked_active_inbox_counterpart__when__reading__then__return_body_with_can_reply_false_instead_of_recipient_403() {
        prepareInbox(Long.MAX_VALUE)
        whenever(members.get(Long.MAX_VALUE)).thenReturn(active(Long.MAX_VALUE))
        whenever(blocks.blocked(7, Long.MAX_VALUE)).thenReturn(true)

        mvc.perform(authenticated(PATH))
            .andExpect(status().isOk())
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(header().doesNotExist("Set-Cookie"))
            .andExpect(
                content().json(
                    expectedNote("INBOX", null, false, Long.MAX_VALUE, "회원", false, null),
                    JsonCompareMode.STRICT,
                ),
            )

        verify(members).get(7)
        verify(members).get(Long.MAX_VALUE)
        verify(clock).instant()
        verify(notes).find(7, NOTE, NOW)
        verify(blocks).blocked(7, Long.MAX_VALUE)
        verifyNoMoreInteractions(members, clock, notes, blocks)
        verifyNoInteractions(batches, blockLists, notifications, counts)
    }

    @ParameterizedTest
    @ValueSource(strings = ["NULL", "MISSING", "PENDING"])
    fun given__null_missing_or_pending_counterpart__when__reading_owned_note__then__keep_body_and_null_display_id_without_block_lookup(
        state: String,
    ) {
        val counterpart: Long? = if (state == "NULL") null else Long.MAX_VALUE
        prepareInbox(counterpart)
        if (counterpart != null) {
            whenever(members.get(counterpart)).thenReturn(
                ContactMember(counterpart, "원문을 노출하지 않음", false, state == "PENDING"),
            )
        }

        mvc.perform(authenticated(PATH))
            .andExpect(status().isOk())
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(header().doesNotExist("Set-Cookie"))
            .andExpect(
                content().json(
                    expectedNote("INBOX", null, false, null, "탈퇴한 회원", false, null),
                    JsonCompareMode.STRICT,
                ),
            )

        verify(members).get(7)
        if (counterpart != null) {
            verify(members).get(counterpart)
        }
        verify(clock).instant()
        verify(notes).find(7, NOTE, NOW)
        verifyNoMoreInteractions(members, clock, notes)
        verifyNoInteractions(blocks, batches, blockLists, notifications, counts)
    }

    @Test
    fun given__no_owned_unexpired_mailbox__when__reading__then__return_404_before_counterpart_or_block_lookup() {
        whenever(members.get(7)).thenReturn(active(7))
        whenever(clock.instant()).thenReturn(NOW)
        whenever(notes.find(7, NOTE, NOW)).thenReturn(Optional.empty())

        mvc.perform(authenticated(PATH))
            .andExpect(status().isNotFound())
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(header().doesNotExist("Set-Cookie"))
            .andExpect(content().json("{\"message\":\"쪽지를 찾을 수 없습니다.\"}", JsonCompareMode.STRICT))

        val order = inOrder(members, clock, notes)
        order.verify(members).get(7)
        order.verify(clock).instant()
        order.verify(notes).find(7, NOTE, NOW)
        verifyNoMoreInteractions(members, clock, notes)
        verifyNoInteractions(blocks, batches, blockLists, notifications, counts)
    }

    @Test
    fun given__inactive_current_member__when__reading__then__return_403_before_clock_note_or_counterpart() {
        whenever(members.get(7)).thenReturn(ContactMember(7L, "탈퇴한 회원", false, true))

        mvc.perform(authenticated(PATH))
            .andExpect(status().isForbidden())
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(header().doesNotExist("Set-Cookie"))
            .andExpect(content().json("{\"message\":\"이용할 수 없는 회원입니다.\"}", JsonCompareMode.STRICT))

        verify(members).get(7)
        verifyNoMoreInteractions(members)
        verifyNoInteractions(clock, notes, blocks, batches, blockLists, notifications, counts)
    }

    @ParameterizedTest
    @MethodSource("rejectedAuthorizations")
    fun given__missing_or_invalid_jwt_and_malformed_note_id__when__requesting__then__authenticate_before_uuid_binding_and_all_dependencies(
        authorization: String?,
        message: String,
    ) {
        val request = get("/api/v1/notes/not-a-uuid").header("X-User-Id", "7")
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

    @Test
    fun given__authenticated_malformed_note_id__when__binding__then__return_message_only_400_before_user_clock_or_note() {
        mvc.perform(authenticated("/api/v1/notes/not-a-uuid"))
            .andExpect(status().isBadRequest())
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(header().doesNotExist("Set-Cookie"))
            .andExpect(content().json("{\"message\":\"요청 형식과 로그인 정보를 확인해 주세요.\"}", JsonCompareMode.STRICT))
        noDependencies()
    }

    @ParameterizedTest
    @ValueSource(strings = ["MISSING", "INVALID", "VALID"])
    fun given__unexposed_stream_extension_with_no_bad_or_valid_jwt__when__requesting__then__retain_default_403_without_authentication_binding_or_sse_work(
        token: String,
    ) {
        val request = get("/api/v1/notes/stream/extra").accept(MediaType.TEXT_EVENT_STREAM).header("X-User-Id", "7")
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
        assertThat(SecurityContextHolder.getContext().authentication).isNull()
    }

    @ParameterizedTest
    @MethodSource("dependencyFailures")
    fun given__current_clock_note_counterpart_or_block_failure__when__reading__then__preserve_source_specific_503_without_partial_body(
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
                if (phase == "COUNTERPART" || phase == "BLOCK_DAO") {
                    whenever(notes.find(7, NOTE, NOW)).thenReturn(Optional.of(inbox(Long.MAX_VALUE)))
                    if (phase == "COUNTERPART") {
                        whenever(members.get(Long.MAX_VALUE)).thenThrow(failure)
                    } else {
                        whenever(members.get(Long.MAX_VALUE)).thenReturn(active(Long.MAX_VALUE))
                        whenever(blocks.blocked(7, Long.MAX_VALUE)).thenThrow(failure)
                    }
                } else {
                    whenever(notes.find(7, NOTE, NOW)).thenThrow(failure)
                }
            }
        }

        mvc.perform(authenticated(PATH))
            .andExpect(status().isServiceUnavailable())
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(header().doesNotExist("Set-Cookie"))
            .andExpect(content().json(mapper.writeValueAsString(mapOf("message" to message)), JsonCompareMode.STRICT))

        verify(members).get(7)
        if (phase == "CURRENT") {
            verifyNoInteractions(clock, notes, blocks)
        } else {
            verify(clock).instant()
            if (phase == "CLOCK") {
                verifyNoInteractions(notes, blocks)
            } else {
                verify(notes).find(7, NOTE, NOW)
                if (phase == "COUNTERPART" || phase == "BLOCK_DAO") {
                    verify(members).get(Long.MAX_VALUE)
                }
                if (phase == "BLOCK_DAO") {
                    verify(blocks).blocked(7, Long.MAX_VALUE)
                } else {
                    verifyNoInteractions(blocks)
                }
            }
        }
        verifyNoMoreInteractions(members, clock, notes, blocks)
        verifyNoInteractions(batches, blockLists, notifications, counts)
        assertThat(SecurityContextHolder.getContext().authentication).isNull()
    }

    @Test
    fun given__static_notification_and_block_routes__when__reading__then__prefer_their_mappings_and_handlers_over_uuid_detail() {
        whenever(members.get(7)).thenReturn(active(7))
        whenever(clock.instant()).thenReturn(NOW)
        whenever(notifications.read(7, null, NOW)).thenReturn(listOf())
        whenever(notifications.unread(7, NOW)).thenReturn(0L)
        whenever(blockLists.count(7)).thenReturn(0L)
        whenever(blockLists.read(7, 0)).thenReturn(listOf())

        mvc.perform(authenticated("/api/v1/notes/notifications"))
            .andExpect(status().isOk())
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(header().doesNotExist("Set-Cookie"))
            .andExpect(
                content().json(
                    "{\"code\":200,\"message\":\"요청이 성공적으로 처리되었습니다.\",\"data\":{\"content\":[],\"nextCursor\":null,\"unreadCount\":0}}",
                    JsonCompareMode.STRICT,
                ),
            )
        mvc.perform(authenticated("/api/v1/notes/blocks"))
            .andExpect(status().isOk())
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(header().doesNotExist("Set-Cookie"))
            .andExpect(
                content().json(
                    "{\"code\":200,\"message\":\"요청이 성공적으로 처리되었습니다.\",\"data\":{\"content\":[],\"totalElements\":0,\"totalPages\":0,\"number\":0}}",
                    JsonCompareMode.STRICT,
                ),
            )
        mvc.perform(authenticated("/api/v1/notes/blocks").param("page", "-1"))
            .andExpect(status().isBadRequest())
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(header().doesNotExist("Set-Cookie"))
            .andExpect(content().json("{\"message\":\"페이지 범위를 확인해 주세요.\"}", JsonCompareMode.STRICT))

        verify(members, times(2)).get(7)
        verify(clock).instant()
        verify(notifications).read(7, null, NOW)
        verify(notifications).unread(7, NOW)
        verify(blockLists).count(7)
        verify(blockLists).read(7, 0)
        verifyNoMoreInteractions(members, clock, notifications, blockLists)
        verifyNoInteractions(notes, blocks, batches, counts)
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "GET /api/notes/f2200000-0000-0000-0000-000000000001",
            "GET /api/v1/notes/f2200000-0000-0000-0000-000000000001/",
            "HEAD /api/v1/notes/f2200000-0000-0000-0000-000000000001",
            "OPTIONS /api/v1/notes/f2200000-0000-0000-0000-000000000001",
            "POST /api/v1/notes/f2200000-0000-0000-0000-000000000001",
            "PUT /api/v1/notes/f2200000-0000-0000-0000-000000000001/read/extra",
            "PUT /api/v1/notes/f2200000-0000-0000-0000-000000000001/favorite/extra",
            "DELETE /api/v1/notes/f2200000-0000-0000-0000-000000000001/extra",
        ],
    )
    fun given__unexposed_neighbor_alias_or_method__when__requesting_with_valid_jwt__then__retain_default_deny_without_note_or_writer_work(
        request: String,
    ) {
        val parts = request.split(" ", limit = 2)
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
    fun given__existing_admin_count_permission_and_runtime_error__when__requesting__then__keep_admin_403_and_common_500_outside_note_advice() {
        whenever(counts.count()).thenThrow(IllegalStateException("synthetic-private-count"))

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
        verifyNoInteractions(members, clock, notes, blocks, batches, blockLists, notifications)
        assertThat(SecurityContextHolder.getContext().authentication).isNull()
    }

    private fun expectedNote(
        direction: String,
        readAt: Instant?,
        favorite: Boolean,
        counterpart: Long?,
        nickname: String,
        canReply: Boolean,
        contextHref: String?,
    ): String {
        val data = mapper.createObjectNode()
            .put("noteId", NOTE.toString())
            .put("body", BODY)
            .put("direction", direction)
            .put("createdAt", CREATED_AT.toString())
            .put("readAt", readAt?.toString())
            .put("favorite", favorite)
            .put("counterpartNickname", nickname)
            .put("canReply", canReply)
            .put("contextHref", contextHref)
        if (counterpart == null) {
            data.putNull("counterpartId")
        } else {
            data.put("counterpartId", counterpart)
        }
        return mapper.writeValueAsString(mapOf("code" to 200, "message" to "요청이 성공적으로 처리되었습니다.", "data" to data))
    }

    private fun prepareInbox(counterpart: Long?) {
        whenever(members.get(7)).thenReturn(active(7))
        whenever(clock.instant()).thenReturn(NOW)
        whenever(notes.find(7, NOTE, NOW)).thenReturn(Optional.of(inbox(counterpart)))
    }

    private fun authenticated(path: String): MockHttpServletRequestBuilder {
        return get(path).header("Authorization", "Bearer " + F3TestInputs.token(7, "ROLE_USER"))
    }

    private fun noDependencies() {
        verifyNoInteractions(members, clock, notes, blocks, batches, blockLists, notifications, counts)
    }

    companion object {
        private val NOTE: UUID = UUID.fromString("f2200000-0000-0000-0000-000000000001")
        private val PATH = "/api/v1/notes/" + NOTE
        private const val BODY = "  원문\n<태그>  "
        private val NOW: Instant = Instant.parse("2031-01-01T00:00:00.999999Z")
        private val CREATED_AT: Instant = Instant.parse("2030-12-31T15:00:00.123456Z")
        private const val SECURITY_CACHE = "no-cache, no-store, max-age=0, must-revalidate"

        @JvmStatic
        fun directions(): Stream<Arguments> {
            return Stream.of(
                Arguments.of("INBOX", Long.MAX_VALUE, 7L, null, true, "POST", "/community/13", true),
                Arguments.of(
                    "SENT",
                    7L,
                    Long.MAX_VALUE,
                    CREATED_AT.plusNanos(1000),
                    false,
                    "REPORT",
                    "/reports/13",
                    false,
                ),
            )
        }

        @JvmStatic
        fun rejectedAuthorizations(): Stream<Arguments> {
            return Stream.of(
                Arguments.of(null, "인증 토큰이 필요합니다."),
                Arguments.of("Bearer invalid", "유효하지 않거나 만료된 토큰입니다."),
            )
        }

        @JvmStatic
        fun dependencyFailures(): Stream<Arguments> {
            val member = "회원 정보를 확인할 수 없습니다. 잠시 후 다시 시도해 주세요."
            val storage = "쪽지 저장소를 사용할 수 없습니다. 잠시 후 다시 시도해 주세요."
            val generic = "쪽지를 처리하지 못했습니다. 잠시 후 다시 시도해 주세요."
            return Stream.of(
                Arguments.of(
                    "CURRENT",
                    ContactMemberUnavailableException(DataAccessResourceFailureException("synthetic-private-current")),
                    member,
                ),
                Arguments.of(
                    "COUNTERPART",
                    ContactMemberUnavailableException(TransactionSystemException("synthetic-private-counterpart")),
                    member,
                ),
                Arguments.of(
                    "NOTE_DAO",
                    DataAccessResourceFailureException("synthetic-private-note", SQLException("synthetic-private-password")),
                    storage,
                ),
                Arguments.of("BLOCK_DAO", DataAccessResourceFailureException("synthetic-private-block"), storage),
                Arguments.of("BEGIN", CannotCreateTransactionException("synthetic-private-begin"), storage),
                Arguments.of("COMMIT", TransactionSystemException("synthetic-private-commit"), storage),
                Arguments.of("TIMEOUT", TransactionTimedOutException("synthetic-private-timeout"), storage),
                Arguments.of("USAGE", IllegalTransactionStateException("synthetic-private-usage"), generic),
                Arguments.of("RUNTIME", IllegalStateException("synthetic-private-body"), generic),
                Arguments.of("CLOCK", IllegalStateException("synthetic-private-clock"), generic),
            )
        }

        private fun inbox(counterpart: Long?): PrivateNoteReader.StoredNote {
            return PrivateNoteReader.StoredNote(NOTE, counterpart, 7L, BODY, null, null, CREATED_AT, "INBOX", null, false)
        }

        private fun active(id: Long): ContactMember = ContactMember(id, "회원", true, false)
    }
}
