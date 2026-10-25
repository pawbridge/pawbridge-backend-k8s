package com.pawbridge.backend

import com.fasterxml.jackson.databind.ObjectMapper
import com.ninjasquad.springmockk.MockkBean
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
import io.mockk.confirmVerified
import io.mockk.every
import io.mockk.verify
import io.mockk.verifySequence
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.dao.DataAccessResourceFailureException
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.test.context.ContextConfiguration
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

    @MockkBean
    lateinit var members: ContactMemberQuery

    @MockkBean
    lateinit var batches: ContactMembersQuery

    @MockkBean
    lateinit var notes: NoteNotificationReader

    @MockkBean(name = "readClock")
    lateinit var clock: Clock

    @MockkBean
    lateinit var recipientBlocks: NoteBlockReader

    @MockkBean
    lateinit var statusBlocks: NoteBlockStatusReader

    @MockkBean
    lateinit var blocks: NoteBlockListReader

    @MockkBean
    lateinit var counts: TotalUserCountQuery

    @ParameterizedTest
    @ValueSource(strings = ["ROLE_USER", "ROLE_ADMIN", "ROLE_SHELTER"])
    fun `일반 역할과 위조 신원이 주어졌을 때__알림을 읽으면__널 허용 응답과 원본 행위자 ID를 유지한다`(
        role: String,
    ) {
        // given
        every { members.get(any()) } returns active(7)
        every { clock.instant() } returns NOW
        every { notes.read(any(), any(), any()) } returns listOf(
            NoteNotificationReader.Row(FIRST, Long.MAX_VALUE, CREATED_AT, null),
            NoteNotificationReader.Row(SECOND, 8L, CREATED_AT.minusNanos(1000), NOW),
            NoteNotificationReader.Row(THIRD, null, CREATED_AT.minusNanos(2000), null),
        )
        every { batches.getAll(any()) } returns listOf(
            ContactMember(8L, "원래 닉네임을 노출하지 않음", false, true),
            ContactMember(Long.MAX_VALUE, "  ", true, false),
        )
        every { notes.unread(any(), any()) } returns 5_000_000_000L

        // when
        val result = mvc.perform(
            authenticatedNotifications(role)
                .header("X-User-Id", "999")
                .header("X-User-Role", "ROLE_ADMIN")
                .param("userId", "999")
                .param("role", "ROLE_ADMIN")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"userId\":999,\"cursor\":\"forged\"}"),
        )

        // then
        val response = result
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
        verifySequence {
            members.get(7)
            clock.instant()
            notes.read(7, null, NOW)
            batches.getAll(listOf(Long.MAX_VALUE, 8L))
            notes.unread(7, NOW)
        }
        confirmVerified(members, clock, notes, batches, recipientBlocks, statusBlocks, blocks, counts)
        assertThat(SecurityContextHolder.getContext().authentication).isNull()
    }

    @Test
    fun `유효한 커서와 빈 페이지일 때__읽으면__배치 없이 null 다음 커서와 원본 미읽음 수를 준다`() {
        // given
        every { members.get(any()) } returns active(7)
        every { clock.instant() } returns NOW
        every { notes.read(any(), any(), any()) } returns listOf()
        every { notes.unread(any(), any()) } returns Long.MAX_VALUE

        // when
        val result = mvc.perform(authenticatedNotifications("ROLE_USER").param("cursor", FIRST.toString()))

        // then
        result
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
        verifySequence {
            members.get(7)
            clock.instant()
            notes.read(7, FIRST, NOW)
            notes.unread(7, NOW)
        }
        confirmVerified(members, clock, notes, batches, recipientBlocks, statusBlocks, blocks, counts)
    }

    @ParameterizedTest
    @MethodSource("rejectedAuthorizations")
    fun `토큰이 없거나 잘못되고 커서가 깨졌을 때__요청하면__바인딩과 모든 의존성 전에 인증 실패를 준다`(
        authorization: String?,
        message: String,
    ) {
        // given
        val request = get(PATH).param("cursor", "not-a-uuid").header("X-User-Id", "7")
        if (authorization != null) {
            request.header("Authorization", authorization)
        }

        // when
        val result = mvc.perform(request)

        // then
        result
            .andExpect(status().isUnauthorized())
            .andExpect(header().doesNotExist("Set-Cookie"))
            .andExpect(
                content().json(
                    mapper.writeValueAsString(NoteNotificationsController.Response<Void?>(401, message, null)),
                    JsonCompareMode.STRICT,
                ),
            )
        confirmVerified(members, batches, clock, notes, recipientBlocks, statusBlocks, blocks, counts)
        assertThat(SecurityContextHolder.getContext().authentication).isNull()
    }

    @Test
    fun `변환할 수 없는 커서가 주어졌을 때__바인딩하면__회원과 시계 전에 메시지만 담은 400을 준다`() {
        // given
        val request = authenticatedNotifications("ROLE_USER").param("cursor", "not-a-uuid")

        // when
        val result = mvc.perform(request)

        // then
        result
            .andExpect(status().isBadRequest())
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(header().doesNotExist("Set-Cookie"))
            .andExpect(content().json("{\"message\":\"요청 형식과 로그인 정보를 확인해 주세요.\"}", JsonCompareMode.STRICT))
        confirmVerified(members, batches, clock, notes, recipientBlocks, statusBlocks, blocks, counts)
    }

    @Test
    fun `탈퇴 대기 호출자가 주어졌을 때__읽으면__시계 목록 배치 미읽음 전에 403을 준다`() {
        // given
        every { members.get(any()) } returns ContactMember(7L, "탈퇴한 회원", false, true)

        // when
        val result = mvc.perform(authenticatedNotifications("ROLE_USER"))

        // then
        result
            .andExpect(status().isForbidden())
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(header().doesNotExist("Set-Cookie"))
            .andExpect(content().json("{\"message\":\"이용할 수 없는 회원입니다.\"}", JsonCompareMode.STRICT))
        verify(exactly = 1) { members.get(7) }
        confirmVerified(members, batches, clock, notes, recipientBlocks, statusBlocks, blocks, counts)
    }

    @ParameterizedTest
    @MethodSource("dependencyFailures")
    fun `회원 목록 시계 미읽음 조회가 실패했을 때__읽으면__원인별 503을 주고 이후 단계를 멈춘다`(
        phase: String,
        failure: RuntimeException,
        message: String,
    ) {
        // given
        if (phase == "CURRENT") {
            every { members.get(any()) } throws failure
        } else {
            every { members.get(any()) } returns active(7)
            if (phase == "CLOCK") {
                every { clock.instant() } throws failure
            } else {
                every { clock.instant() } returns NOW
                if (phase == "BATCH") {
                    every { notes.read(any(), any(), any()) } returns listOf(NoteNotificationReader.Row(FIRST, 8L, CREATED_AT, null))
                    every { batches.getAll(any()) } throws failure
                } else if (phase == "UNREAD_DAO") {
                    every { notes.read(any(), any(), any()) } returns listOf()
                    every { notes.unread(any(), any()) } throws failure
                } else {
                    every { notes.read(any(), any(), any()) } throws failure
                }
            }
        }

        // when
        val result = mvc.perform(authenticatedNotifications("ROLE_USER"))

        // then
        result
            .andExpect(status().isServiceUnavailable())
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(header().doesNotExist("Set-Cookie"))
            .andExpect(content().json(mapper.writeValueAsString(mapOf("message" to message)), JsonCompareMode.STRICT))
        verify(exactly = 1) { members.get(7) }
        if (phase != "CURRENT") {
            verify(exactly = 1) { clock.instant() }
            if (phase != "CLOCK") {
                verify(exactly = 1) { notes.read(7, null, NOW) }
                if (phase == "BATCH") {
                    verify(exactly = 1) { batches.getAll(listOf(8L)) }
                } else if (phase == "UNREAD_DAO") {
                    verify(exactly = 1) { notes.unread(7, NOW) }
                }
            }
        }
        confirmVerified(members, clock, notes, batches, recipientBlocks, statusBlocks, blocks, counts)
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
    fun `노출되지 않은 인접 경로나 메서드일 때__유효한 토큰으로 요청하면__모든 의존성 전에 거부한다`(
        request: String,
    ) {
        // given
        val parts = request.split(" ", limit = 2)

        // when
        val result = mvc.perform(
            request(HttpMethod.valueOf(parts[0]), parts[1])
                .header("Authorization", "Bearer " + F3TestInputs.token(7, "ROLE_USER")),
        )

        // then
        result
            .andExpect(status().isForbidden())
            .andExpect(header().doesNotExist("Set-Cookie"))
        confirmVerified(members, batches, clock, notes, recipientBlocks, statusBlocks, blocks, counts)
    }

    @Test
    fun `기존 수신자 상태 차단 목록 관리자 경로일 때__각자 정책을 실행하면__알림 작업 없이 범위 응답을 유지한다`() {
        // given
        every { members.get(any()) } returns active(7)
        every { blocks.count(any()) } returns 0L
        every { blocks.read(any(), any()) } returns listOf()

        // when
        val recipient = mvc.perform(
            get("/api/v1/notes/recipients/7").header("Authorization", "Bearer " + F3TestInputs.token(7, "ROLE_USER")),
        )
        val blockStatus = mvc.perform(
            get("/api/v1/notes/blocks/status")
                .param("memberIds", "0")
                .header("Authorization", "Bearer " + F3TestInputs.token(7, "ROLE_USER")),
        )
        val blockList = mvc.perform(
            get("/api/v1/notes/blocks").header("Authorization", "Bearer " + F3TestInputs.token(7, "ROLE_USER")),
        )
        val admin = mvc.perform(
            get("/api/v1/admin/users/stats/total-users")
                .header("Authorization", "Bearer " + F3TestInputs.token(7, "ROLE_USER")),
        )

        // then
        recipient
            .andExpect(status().isForbidden())
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(content().json("{\"message\":\"이 상대에게 쪽지를 보낼 수 없습니다.\"}", JsonCompareMode.STRICT))
        blockStatus
            .andExpect(status().isBadRequest())
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(content().json("{\"message\":\"작성자 식별자는 1~100개의 양수여야 합니다.\"}", JsonCompareMode.STRICT))
        blockList
            .andExpect(status().isOk())
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(
                content().json(
                    "{\"code\":200,\"message\":\"요청이 성공적으로 처리되었습니다.\",\"data\":{" +
                        "\"content\":[],\"totalElements\":0,\"totalPages\":0,\"number\":0}}",
                    JsonCompareMode.STRICT,
                ),
            )
        admin
            .andExpect(status().isForbidden())
            .andExpect(
                content().json("{\"code\":403,\"message\":\"관리자 권한이 필요합니다.\",\"data\":null}", JsonCompareMode.STRICT),
            )
        verify(exactly = 2) { members.get(7) }
        verify(exactly = 1) { blocks.count(7) }
        verify(exactly = 1) { blocks.read(7, 0) }
        confirmVerified(members, blocks, batches, clock, notes, recipientBlocks, statusBlocks, counts)
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
