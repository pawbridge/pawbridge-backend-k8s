package com.pawbridge.backend

import com.fasterxml.jackson.databind.ObjectMapper
import com.pawbridge.backend.boot.configuration.ReadSecurityConfiguration
import com.pawbridge.backend.community.internal.note.application.ListNoteBlocks
import com.pawbridge.backend.community.internal.note.application.NoteBlockListReader
import com.pawbridge.backend.community.internal.note.application.NoteBlockReader
import com.pawbridge.backend.community.internal.note.application.NoteBlockStatusReader
import com.pawbridge.backend.community.internal.note.application.NoteNotificationReader
import com.pawbridge.backend.community.internal.note.application.ReadBlockedAuthors
import com.pawbridge.backend.community.internal.note.application.ReadNoteNotifications
import com.pawbridge.backend.community.internal.note.application.ReadNoteRecipient
import com.pawbridge.backend.community.internal.note.web.NoteBlockListController
import com.pawbridge.backend.community.internal.note.web.NoteBlockListExceptionHandler
import com.pawbridge.backend.community.internal.note.web.NoteBlockStatusController
import com.pawbridge.backend.community.internal.note.web.NoteBlockStatusExceptionHandler
import com.pawbridge.backend.community.internal.note.web.NoteNotificationsController
import com.pawbridge.backend.community.internal.note.web.NoteNotificationsExceptionHandler
import com.pawbridge.backend.community.internal.note.web.NoteRecipientController
import com.pawbridge.backend.community.internal.note.web.NoteRecipientExceptionHandler
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
import java.util.UUID
import java.util.stream.Stream

@WebMvcTest(
    controllers = [NoteNotificationsController::class],
    excludeAutoConfiguration = [UserDetailsServiceAutoConfiguration::class],
)
@ContextConfiguration(
    classes = [
        NoteNotificationsController::class,
        NoteNotificationsExceptionHandler::class,
        ReadNoteNotifications::class,
        NoteRecipientController::class,
        NoteRecipientExceptionHandler::class,
        ReadNoteRecipient::class,
        NoteBlockStatusController::class,
        NoteBlockStatusExceptionHandler::class,
        ReadBlockedAuthors::class,
        NoteBlockListController::class,
        NoteBlockListExceptionHandler::class,
        ListNoteBlocks::class,
        TotalUserCountController::class,
        TotalUserCountExceptionHandler::class,
        ReadSecurityConfiguration::class,
        F3TestInputs.SecurityInputs::class,
    ],
)
class NoteNotificationsHttpTest {
    @Autowired
    lateinit var mvc: MockMvc

    @Autowired
    lateinit var mapper: ObjectMapper

    @MockitoBean
    lateinit var members: ContactMemberQuery

    @MockitoBean
    lateinit var batches: ContactMembersQuery

    @MockitoBean
    lateinit var notes: NoteNotificationReader

    @MockitoBean(name = "readClock")
    lateinit var clock: Clock

    @MockitoBean
    lateinit var recipientBlocks: NoteBlockReader

    @MockitoBean
    lateinit var statusBlocks: NoteBlockStatusReader

    @MockitoBean
    lateinit var blocks: NoteBlockListReader

    @MockitoBean
    lateinit var counts: TotalUserCountQuery

    @ParameterizedTest
    @ValueSource(strings = ["ROLE_USER", "ROLE_ADMIN", "ROLE_SHELTER"])
    fun given__ordinary_role_and_spoofed_identity__when__reading_notifications__then__keep_exact_nullable_wire_raw_actor_id_and_one_shared_instant(
        role: String,
    ) {
        whenever(members.get(7)).thenReturn(active(7))
        whenever(clock.instant()).thenReturn(NOW)
        whenever(notes.read(7, null, NOW)).thenReturn(
            listOf(
                NoteNotificationReader.Row(FIRST, Long.MAX_VALUE, CREATED_AT, null),
                NoteNotificationReader.Row(SECOND, 8L, CREATED_AT.minusNanos(1000), NOW),
                NoteNotificationReader.Row(THIRD, null, CREATED_AT.minusNanos(2000), null),
            ),
        )
        whenever(batches.getAll(listOf(Long.MAX_VALUE, 8L))).thenReturn(
            listOf(
                ContactMember(8L, "원래 닉네임을 노출하지 않음", false, true),
                ContactMember(Long.MAX_VALUE, "  ", true, false),
            ),
        )
        whenever(notes.unread(7, NOW)).thenReturn(5_000_000_000L)

        val response = mvc.perform(
            authenticatedNotifications(role)
                .header("X-User-Id", "999")
                .header("X-User-Role", "ROLE_ADMIN")
                .param("userId", "999")
                .param("role", "ROLE_ADMIN")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"userId\":999,\"cursor\":\"forged\"}"),
        )
            .andExpect(status().isOk())
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(header().doesNotExist("Set-Cookie"))
            .andExpect(
                content().json(
                    "{\"code\":200,\"message\":\"요청이 성공적으로 처리되었습니다.\",\"data\":{" +
                        "\"content\":[{\"noteId\":\"" + FIRST + "\",\"kind\":\"PRIVATE_NOTE\",\"actorId\":" + Long.MAX_VALUE +
                        ",\"actorNickname\":\"  \",\"createdAt\":\"2026-10-19T15:00:00.123456Z\",\"read\":false,\"href\":\"/notes/" +
                        FIRST + "\"}," +
                        "{\"noteId\":\"" + SECOND + "\",\"kind\":\"PRIVATE_NOTE\",\"actorId\":null,\"actorNickname\":\"탈퇴한 회원\"," +
                        "\"createdAt\":\"2026-10-19T15:00:00.123455Z\",\"read\":true,\"href\":\"/notes/" + SECOND + "\"}," +
                        "{\"noteId\":\"" + THIRD + "\",\"kind\":\"PRIVATE_NOTE\",\"actorId\":null,\"actorNickname\":\"탈퇴한 회원\"," +
                        "\"createdAt\":\"2026-10-19T15:00:00.123454Z\",\"read\":false,\"href\":\"/notes/" + THIRD + "\"}]," +
                        "\"nextCursor\":null,\"unreadCount\":5000000000}}",
                    JsonCompareMode.STRICT,
                ),
            )
            .andReturn().response

        val actorId = mapper.readTree(response.contentAsString).path("data").path("content").get(0).path("actorId")
        assertThat(actorId.isIntegralNumber()).isTrue()
        assertThat(actorId.longValue()).isEqualTo(Long.MAX_VALUE)
        val order = inOrder(members, clock, notes, batches)
        order.verify(members).get(7)
        order.verify(clock).instant()
        order.verify(notes).read(7, null, NOW)
        order.verify(batches).getAll(listOf(Long.MAX_VALUE, 8L))
        order.verify(notes).unread(7, NOW)
        verifyNoMoreInteractions(members, clock, notes, batches)
        verifyNoInteractions(recipientBlocks, statusBlocks, blocks, counts)
        assertThat(SecurityContextHolder.getContext().authentication).isNull()
    }

    @Test
    fun given__valid_cursor_and_empty_page__when__reading__then__forward_uuid_and_keep_null_next_cursor_and_raw_unread_count_without_batch() {
        whenever(members.get(7)).thenReturn(active(7))
        whenever(clock.instant()).thenReturn(NOW)
        whenever(notes.read(7, FIRST, NOW)).thenReturn(listOf())
        whenever(notes.unread(7, NOW)).thenReturn(Long.MAX_VALUE)

        mvc.perform(authenticatedNotifications("ROLE_USER").param("cursor", FIRST.toString()))
            .andExpect(status().isOk())
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(header().doesNotExist("Set-Cookie"))
            .andExpect(
                content().json(
                    "{\"code\":200,\"message\":\"요청이 성공적으로 처리되었습니다.\",\"data\":{" +
                        "\"content\":[],\"nextCursor\":null,\"unreadCount\":" + Long.MAX_VALUE + "}}",
                    JsonCompareMode.STRICT,
                ),
            )

        val order = inOrder(members, clock, notes)
        order.verify(members).get(7)
        order.verify(clock).instant()
        order.verify(notes).read(7, FIRST, NOW)
        order.verify(notes).unread(7, NOW)
        verifyNoMoreInteractions(members, clock, notes)
        verifyNoInteractions(batches, recipientBlocks, statusBlocks, blocks, counts)
    }

    @ParameterizedTest
    @MethodSource("rejectedAuthorizations")
    fun given__missing_or_invalid_jwt_and_bad_cursor__when__requesting__then__authenticate_before_binding_and_every_dependency(
        authorization: String?,
        message: String,
    ) {
        val request = get(PATH).param("cursor", "not-a-uuid").header("X-User-Id", "7")
        if (authorization != null) {
            request.header("Authorization", authorization)
        }

        mvc.perform(request)
            .andExpect(status().isUnauthorized())
            .andExpect(header().doesNotExist("Set-Cookie"))
            .andExpect(
                content().json(
                    mapper.writeValueAsString(NoteNotificationsController.Response<Void?>(401, message, null)),
                    JsonCompareMode.STRICT,
                ),
            )

        verifyNoInteractions(members, batches, clock, notes, recipientBlocks, statusBlocks, blocks, counts)
        assertThat(SecurityContextHolder.getContext().authentication).isNull()
    }

    @Test
    fun given__authenticated_unconvertible_cursor__when__binding__then__return_message_only_400_before_member_or_clock() {
        mvc.perform(authenticatedNotifications("ROLE_USER").param("cursor", "not-a-uuid"))
            .andExpect(status().isBadRequest())
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(header().doesNotExist("Set-Cookie"))
            .andExpect(content().json("{\"message\":\"요청 형식과 로그인 정보를 확인해 주세요.\"}", JsonCompareMode.STRICT))

        verifyNoInteractions(members, batches, clock, notes, recipientBlocks, statusBlocks, blocks, counts)
    }

    @Test
    fun given__pending_current_member__when__reading__then__return_inactive_403_before_clock_list_batch_or_unread() {
        whenever(members.get(7)).thenReturn(ContactMember(7L, "탈퇴한 회원", false, true))

        mvc.perform(authenticatedNotifications("ROLE_USER"))
            .andExpect(status().isForbidden())
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(header().doesNotExist("Set-Cookie"))
            .andExpect(content().json("{\"message\":\"이용할 수 없는 회원입니다.\"}", JsonCompareMode.STRICT))

        verify(members).get(7)
        verifyNoMoreInteractions(members)
        verifyNoInteractions(batches, clock, notes, recipientBlocks, statusBlocks, blocks, counts)
    }

    @ParameterizedTest
    @MethodSource("dependencyFailures")
    fun given__member_list_clock_or_unread_failure__when__reading__then__retain_source_specific_503_and_stop_later_phases(
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
                if (phase == "BATCH") {
                    whenever(notes.read(7, null, NOW)).thenReturn(listOf(NoteNotificationReader.Row(FIRST, 8L, CREATED_AT, null)))
                    whenever(batches.getAll(listOf(8L))).thenThrow(failure)
                } else if (phase == "UNREAD_DAO") {
                    whenever(notes.read(7, null, NOW)).thenReturn(listOf())
                    whenever(notes.unread(7, NOW)).thenThrow(failure)
                } else {
                    whenever(notes.read(7, null, NOW)).thenThrow(failure)
                }
            }
        }

        mvc.perform(authenticatedNotifications("ROLE_USER"))
            .andExpect(status().isServiceUnavailable())
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(header().doesNotExist("Set-Cookie"))
            .andExpect(content().json(mapper.writeValueAsString(mapOf("message" to message)), JsonCompareMode.STRICT))

        verify(members).get(7)
        if (phase == "CURRENT") {
            verifyNoInteractions(clock, notes, batches)
        } else {
            verify(clock).instant()
            if (phase == "CLOCK") {
                verifyNoInteractions(notes, batches)
            } else {
                verify(notes).read(7, null, NOW)
                if (phase == "BATCH") {
                    verify(batches).getAll(listOf(8L))
                } else {
                    verifyNoInteractions(batches)
                    if (phase == "UNREAD_DAO") {
                        verify(notes).unread(7, NOW)
                    }
                }
            }
        }
        verifyNoMoreInteractions(members, clock, notes, batches)
        verifyNoInteractions(recipientBlocks, statusBlocks, blocks, counts)
        assertThat(SecurityContextHolder.getContext().authentication).isNull()
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "GET /api/v1/notes/stream/extra", "GET /api/v1/notes/notifications/", "GET /api/notes/notifications",
            "HEAD /api/v1/notes/notifications", "OPTIONS /api/v1/notes/notifications",
            "POST /api/v1/notes/notifications", "PUT /api/v1/notes/notifications",
            "DELETE /api/v1/notes/notifications/extra",
        ],
    )
    fun given__unexposed_neighbor_path_or_method__when__requesting_with_a_valid_token__then__deny_before_all_dependencies(
        request: String,
    ) {
        val parts = request.split(" ", limit = 2)

        mvc.perform(
            request(HttpMethod.valueOf(parts[0]), parts[1])
                .header("Authorization", "Bearer " + F3TestInputs.token(7, "ROLE_USER")),
        )
            .andExpect(status().isForbidden())
            .andExpect(header().doesNotExist("Set-Cookie"))

        verifyNoInteractions(members, batches, clock, notes, recipientBlocks, statusBlocks, blocks, counts)
    }

    @Test
    fun given__existing_recipient_status_block_list_and_admin_paths__when__their_own_policies_run__then__retain_scoped_wire_without_notification_work() {
        whenever(members.get(7)).thenReturn(active(7))
        whenever(blocks.count(7)).thenReturn(0L)
        whenever(blocks.read(7, 0)).thenReturn(listOf())

        mvc.perform(
            get("/api/v1/notes/recipients/7").header("Authorization", "Bearer " + F3TestInputs.token(7, "ROLE_USER")),
        )
            .andExpect(status().isForbidden())
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(content().json("{\"message\":\"이 상대에게 쪽지를 보낼 수 없습니다.\"}", JsonCompareMode.STRICT))
        mvc.perform(
            get("/api/v1/notes/blocks/status")
                .param("memberIds", "0")
                .header("Authorization", "Bearer " + F3TestInputs.token(7, "ROLE_USER")),
        )
            .andExpect(status().isBadRequest())
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(content().json("{\"message\":\"작성자 식별자는 1~100개의 양수여야 합니다.\"}", JsonCompareMode.STRICT))
        mvc.perform(get("/api/v1/notes/blocks").header("Authorization", "Bearer " + F3TestInputs.token(7, "ROLE_USER")))
            .andExpect(status().isOk())
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(
                content().json(
                    "{\"code\":200,\"message\":\"요청이 성공적으로 처리되었습니다.\",\"data\":{" +
                        "\"content\":[],\"totalElements\":0,\"totalPages\":0,\"number\":0}}",
                    JsonCompareMode.STRICT,
                ),
            )
        mvc.perform(
            get("/api/v1/admin/users/stats/total-users")
                .header("Authorization", "Bearer " + F3TestInputs.token(7, "ROLE_USER")),
        )
            .andExpect(status().isForbidden())
            .andExpect(
                content().json("{\"code\":403,\"message\":\"관리자 권한이 필요합니다.\",\"data\":null}", JsonCompareMode.STRICT),
            )

        verify(members, times(2)).get(7)
        verifyNoMoreInteractions(members)
        verify(blocks).count(7)
        verify(blocks).read(7, 0)
        verifyNoMoreInteractions(blocks)
        verifyNoInteractions(batches, clock, notes, recipientBlocks, statusBlocks, counts)
        assertThat(SecurityContextHolder.getContext().authentication).isNull()
    }

    private fun authenticatedNotifications(role: String): MockHttpServletRequestBuilder {
        return get(PATH).header("Authorization", "Bearer " + F3TestInputs.token(7, role))
    }

    companion object {
        private const val PATH = "/api/v1/notes/notifications"
        private val NOW: Instant = Instant.parse("2026-10-19T15:00:00.999999Z")
        private val CREATED_AT: Instant = Instant.parse("2026-10-19T15:00:00.123456Z")
        private val FIRST: UUID = UUID.fromString("ffffffff-ffff-ffff-ffff-fffffffffff1")
        private val SECOND: UUID = UUID.fromString("ffffffff-ffff-ffff-ffff-fffffffffff2")
        private val THIRD: UUID = UUID.fromString("ffffffff-ffff-ffff-ffff-fffffffffff3")

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
                    ContactMemberUnavailableException(DataAccessResourceFailureException("synthetic-private-caller")),
                    member,
                ),
                Arguments.of(
                    "BATCH",
                    ContactMemberUnavailableException(TransactionSystemException("synthetic-private-batch-commit")),
                    member,
                ),
                Arguments.of(
                    "LIST_DAO",
                    DataAccessResourceFailureException("synthetic-private-list", SQLException("synthetic-private-password")),
                    storage,
                ),
                Arguments.of("UNREAD_DAO", DataAccessResourceFailureException("synthetic-private-unread"), storage),
                Arguments.of("BEGIN", CannotCreateTransactionException("synthetic-private-begin"), storage),
                Arguments.of("COMMIT", TransactionSystemException("synthetic-private-commit"), storage),
                Arguments.of("TIMEOUT", TransactionTimedOutException("synthetic-private-timeout"), storage),
                Arguments.of("USAGE", IllegalTransactionStateException("synthetic-private-usage"), generic),
                Arguments.of("RUNTIME", IllegalStateException("Bearer synthetic-private-token"), generic),
                Arguments.of("CLOCK", IllegalStateException("synthetic-private-clock"), generic),
            )
        }

        private fun active(id: Long): ContactMember = ContactMember(id, "본인", true, false)
    }
}
