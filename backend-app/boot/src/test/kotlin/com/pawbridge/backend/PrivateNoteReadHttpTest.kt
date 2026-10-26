package com.pawbridge.backend

import com.fasterxml.jackson.databind.ObjectMapper
import com.ninjasquad.springmockk.MockkBean
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

    @MockkBean
    lateinit var members: ContactMemberQuery

    @MockkBean
    lateinit var notes: PrivateNoteReader

    @MockkBean
    lateinit var blocks: NoteBlockReader

    @MockkBean(name = "readClock")
    lateinit var clock: Clock

    @MockkBean
    lateinit var batches: ContactMembersQuery

    @MockkBean
    lateinit var blockLists: NoteBlockListReader

    @MockkBean
    lateinit var notifications: NoteNotificationReader

    @MockkBean
    lateinit var counts: TotalUserCountQuery

    @ParameterizedTest
    @MethodSource("directions")
    fun `내 받은 또는 보낸 쪽지와 위조 헤더__상세를 읽으면__상대 정보와 조건부 답장 조회를 정확히 보존한다`(
        direction: String,
        sender: Long,
        recipient: Long,
        readAt: Instant?,
        favorite: Boolean,
        contextType: String,
        contextHref: String,
        canReply: Boolean,
    ) {
        // given
        val request = authenticated(PATH)
            .header("X-User-Id", "999")
            .header("X-User-Role", "ROLE_ADMIN")
            .param("userId", "999")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"userId\":999}")

        every { members.get(7) } returns active(7)
        every { members.get(Long.MAX_VALUE) } returns ContactMember(Long.MAX_VALUE, "  ", true, false)
        every { clock.instant() } returns NOW
        every { notes.find(any(), any(), any()) } returns Optional.of(
            PrivateNoteReader.StoredNote(
                NOTE, sender, recipient, BODY, contextType, 13L, CREATED_AT, direction, readAt, favorite,
            ),
        )
        if (canReply) {
            every { blocks.blocked(any(), any()) } returns false
        }

        // when
        val result = mvc.perform(request)

        // then
        val response = result
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
        verifySequence {
            members.get(7)
            clock.instant()
            notes.find(7, NOTE, NOW)
            members.get(Long.MAX_VALUE)
            if (canReply) {
                blocks.blocked(7, Long.MAX_VALUE)
            }
        }
        confirmVerified(members, clock, notes, blocks)
        confirmVerified(batches, blockLists, notifications, counts)
        assertThat(SecurityContextHolder.getContext().authentication).isNull()
    }

    @Test
    fun `차단했지만 활성인 받은 쪽지 상대일 때__상세를 읽으면__수신자 403 대신 답장 불가로 본문을 돌려준다`() {
        // given
        every { members.get(7) } returns active(7)
        every { members.get(Long.MAX_VALUE) } returns active(Long.MAX_VALUE)
        every { clock.instant() } returns NOW
        every { notes.find(any(), any(), any()) } returns Optional.of(inbox(Long.MAX_VALUE))
        every { blocks.blocked(any(), any()) } returns true

        // when
        val result = mvc.perform(authenticated(PATH))

        // then
        result
            .andExpect(status().isOk())
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(header().doesNotExist("Set-Cookie"))
            .andExpect(
                content().json(
                    expectedNote("INBOX", null, false, Long.MAX_VALUE, "회원", false, null),
                    JsonCompareMode.STRICT,
                ),
            )
        verify(exactly = 1) { members.get(7) }
        verify(exactly = 1) { members.get(Long.MAX_VALUE) }
        verify(exactly = 1) { clock.instant() }
        verify(exactly = 1) { notes.find(7, NOTE, NOW) }
        verify(exactly = 1) { blocks.blocked(7, Long.MAX_VALUE) }
        confirmVerified(members, clock, notes, blocks)
        confirmVerified(batches, blockLists, notifications, counts)
    }

    @ParameterizedTest
    @ValueSource(strings = ["NULL", "MISSING", "PENDING"])
    fun `상대가 없거나 사라졌거나 탈퇴 대기일 때__내 쪽지를 읽으면__차단 조회 없이 본문과 빈 표시 ID를 유지한다`(
        state: String,
    ) {
        // given
        val counterpart: Long? = if (state == "NULL") null else Long.MAX_VALUE

        every { members.get(7) } returns active(7)
        every { clock.instant() } returns NOW
        every { notes.find(any(), any(), any()) } returns Optional.of(inbox(counterpart))
        if (counterpart != null) {
            every { members.get(counterpart) } returns
                ContactMember(counterpart, "원문을 노출하지 않음", false, state == "PENDING")
        }

        // when
        val result = mvc.perform(authenticated(PATH))

        // then
        result
            .andExpect(status().isOk())
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(header().doesNotExist("Set-Cookie"))
            .andExpect(
                content().json(
                    expectedNote("INBOX", null, false, null, "탈퇴한 회원", false, null),
                    JsonCompareMode.STRICT,
                ),
            )
        verify(exactly = 1) { members.get(7) }
        if (counterpart != null) {
            verify(exactly = 1) { members.get(counterpart) }
        }
        verify(exactly = 1) { clock.instant() }
        verify(exactly = 1) { notes.find(7, NOTE, NOW) }
        confirmVerified(members, clock, notes)
        confirmVerified(blocks, batches, blockLists, notifications, counts)
    }

    @Test
    fun `내 것이 아니거나 만료된 쪽지일 때__상세를 읽으면__상대와 차단 조회 전에 404를 돌려준다`() {
        // given
        every { members.get(any()) } returns active(7)
        every { clock.instant() } returns NOW
        every { notes.find(any(), any(), any()) } returns Optional.empty()

        // when
        val result = mvc.perform(authenticated(PATH))

        // then
        result
            .andExpect(status().isNotFound())
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(header().doesNotExist("Set-Cookie"))
            .andExpect(content().json("{\"message\":\"쪽지를 찾을 수 없습니다.\"}", JsonCompareMode.STRICT))
        verifySequence {
            members.get(7)
            clock.instant()
            notes.find(7, NOTE, NOW)
        }
        confirmVerified(members, clock, notes)
        confirmVerified(blocks, batches, blockLists, notifications, counts)
    }

    @Test
    fun `현재 회원이 비활성일 때__상세를 읽으면__시계와 쪽지와 상대 조회 전에 403을 돌려준다`() {
        // given
        every { members.get(any()) } returns ContactMember(7L, "탈퇴한 회원", false, true)

        // when
        val result = mvc.perform(authenticated(PATH))

        // then
        result
            .andExpect(status().isForbidden())
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(header().doesNotExist("Set-Cookie"))
            .andExpect(content().json("{\"message\":\"이용할 수 없는 회원입니다.\"}", JsonCompareMode.STRICT))
        verify(exactly = 1) { members.get(7) }
        confirmVerified(members)
        confirmVerified(clock, notes, blocks, batches, blockLists, notifications, counts)
    }

    @ParameterizedTest
    @MethodSource("dependencyFailures")
    fun `회원 시계 쪽지 상대 또는 차단 조회가 실패했을 때__상세를 읽으면__원인별 503을 주고 부분 본문을 내지 않는다`(
        phase: String,
        failure: RuntimeException,
        message: String,
    ) {
        // given
        if (phase == "CURRENT") {
            every { members.get(any()) } throws failure
        } else {
            every { members.get(7) } returns active(7)
            if (phase == "CLOCK") {
                every { clock.instant() } throws failure
            } else {
                every { clock.instant() } returns NOW
                if (phase == "COUNTERPART" || phase == "BLOCK_DAO") {
                    every { notes.find(any(), any(), any()) } returns Optional.of(inbox(Long.MAX_VALUE))
                    if (phase == "COUNTERPART") {
                        every { members.get(Long.MAX_VALUE) } throws failure
                    } else {
                        every { members.get(Long.MAX_VALUE) } returns active(Long.MAX_VALUE)
                        every { blocks.blocked(any(), any()) } throws failure
                    }
                } else {
                    every { notes.find(any(), any(), any()) } throws failure
                }
            }
        }

        // when
        val result = mvc.perform(authenticated(PATH))

        // then
        result
            .andExpect(status().isServiceUnavailable())
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(header().doesNotExist("Set-Cookie"))
            .andExpect(content().json(mapper.writeValueAsString(mapOf("message" to message)), JsonCompareMode.STRICT))
        verify(exactly = 1) { members.get(7) }
        if (phase != "CURRENT") {
            verify(exactly = 1) { clock.instant() }
        }
        if (phase != "CURRENT" && phase != "CLOCK") {
            verify(exactly = 1) { notes.find(7, NOTE, NOW) }
            if (phase == "COUNTERPART" || phase == "BLOCK_DAO") {
                verify(exactly = 1) { members.get(Long.MAX_VALUE) }
            }
            if (phase == "BLOCK_DAO") {
                verify(exactly = 1) { blocks.blocked(7, Long.MAX_VALUE) }
            }
        }
        confirmVerified(members, clock, notes, blocks)
        confirmVerified(batches, blockLists, notifications, counts)
        assertThat(SecurityContextHolder.getContext().authentication).isNull()
    }

    @Test
    fun `고정 알림 경로와 차단 경로가 있을 때__읽으면__UUID 상세보다 각자의 매핑과 핸들러를 우선한다`() {
        // given
        every { members.get(any()) } returns active(7)
        every { clock.instant() } returns NOW
        every { notifications.read(any(), any(), any()) } returns listOf()
        every { notifications.unread(any(), any()) } returns 0L
        every { blockLists.count(any()) } returns 0L
        every { blockLists.read(any(), any()) } returns listOf()

        // when
        val notificationResult = mvc.perform(authenticated("/api/v1/notes/notifications"))
        val blockResult = mvc.perform(authenticated("/api/v1/notes/blocks"))
        val invalidResult = mvc.perform(authenticated("/api/v1/notes/blocks").param("page", "-1"))

        // then
        notificationResult
            .andExpect(status().isOk())
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(header().doesNotExist("Set-Cookie"))
            .andExpect(
                content().json(
                    "{\"code\":200,\"message\":\"요청이 성공적으로 처리되었습니다.\",\"data\":{\"content\":[],\"nextCursor\":null,\"unreadCount\":0}}",
                    JsonCompareMode.STRICT,
                ),
            )
        blockResult
            .andExpect(status().isOk())
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(header().doesNotExist("Set-Cookie"))
            .andExpect(
                content().json(
                    "{\"code\":200,\"message\":\"요청이 성공적으로 처리되었습니다.\",\"data\":{\"content\":[],\"totalElements\":0,\"totalPages\":0,\"number\":0}}",
                    JsonCompareMode.STRICT,
                ),
            )
        invalidResult
            .andExpect(status().isBadRequest())
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(header().doesNotExist("Set-Cookie"))
            .andExpect(content().json("{\"message\":\"페이지 범위를 확인해 주세요.\"}", JsonCompareMode.STRICT))
        verify(exactly = 2) { members.get(7) }
        verify(exactly = 1) { clock.instant() }
        verify(exactly = 1) { notifications.read(7, null, NOW) }
        verify(exactly = 1) { notifications.unread(7, NOW) }
        verify(exactly = 1) { blockLists.count(7) }
        verify(exactly = 1) { blockLists.read(7, 0) }
        confirmVerified(members, clock, notifications, blockLists)
        confirmVerified(notes, blocks, batches, counts)
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

    private fun authenticated(path: String): MockHttpServletRequestBuilder {
        return get(path).header("Authorization", "Bearer " + F3TestInputs.token(7, "ROLE_USER"))
    }

    private fun noDependencies() {
        confirmVerified(members, clock, notes, blocks, batches, blockLists, notifications, counts)
    }

    companion object {
        private val NOTE: UUID = UUID.fromString("f2200000-0000-0000-0000-000000000001")
        private val PATH = "/api/v1/notes/" + NOTE
        private const val BODY = "  원문\n<태그>  "
        private val NOW: Instant = Instant.parse("2031-01-01T00:00:00.999999Z")
        private val CREATED_AT: Instant = Instant.parse("2030-12-31T15:00:00.123456Z")

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
