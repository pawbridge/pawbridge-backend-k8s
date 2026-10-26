package com.pawbridge.backend

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import com.ninjasquad.springmockk.MockkBean
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

    @MockkBean
    lateinit var members: ContactMemberQuery

    @MockkBean
    lateinit var batches: ContactMembersQuery

    @MockkBean
    lateinit var notes: PrivateNoteListReader

    @MockkBean
    lateinit var blocks: NoteBlockReader

    @MockkBean(name = "readClock")
    lateinit var clock: Clock

    @MockkBean
    lateinit var details: PrivateNoteReader

    @MockkBean
    lateinit var notifications: NoteNotificationReader

    @MockkBean
    lateinit var blockLists: NoteBlockListReader

    @MockkBean
    lateinit var counts: TotalUserCountQuery

    @ParameterizedTest
    @ValueSource(strings = ["INBOX", "SENT"])
    fun `받은쪽지함 또는 보낸쪽지함과 위조 헤더__목록을 조회하면__원문 그대로 다섯과 열 필드를 돌려준다`(
        box: String,
    ) {
        // given
        val sent = box == "SENT"
        val page = if (sent) 1 else 0
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

        every { members.get(any()) } returns active(7)
        every { clock.instant() } returns NOW
        every { notes.count(any(), any(), any(), any()) } returns 11L
        every { notes.read(any(), any(), any(), any(), any()) } returns
            (if (sent) listOf(first) else listOf(first, second, third))
        every { batches.getAll(any()) } returns listOf(ContactMember(Long.MAX_VALUE, "  ", true, false))
        if (!sent) {
            every { blocks.blocked(any(), any()) } returnsMany listOf(false, true)
        }

        // when
        val response = mvc.perform(request)

        // then
        val body = response
            .andExpect(status().isOk())
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(header().doesNotExist("Set-Cookie"))
            .andExpect(content().json(expected.toString(), JsonCompareMode.STRICT))
            .andReturn().response
        val data = mapper.readTree(body.contentAsString).path("data")
        assertThat(data.size()).isEqualTo(5)
        for (note in data.path("content")) {
            assertThat(note.size()).isEqualTo(10)
        }
        assertThat(data.path("content").get(0).path("counterpartId").isIntegralNumber()).isTrue()
        assertThat(data.path("content").get(0).path("counterpartId").longValue()).isEqualTo(Long.MAX_VALUE)
        verifySequence {
            members.get(7)
            clock.instant()
            notes.count(7, box, sent, NOW)
            notes.read(7, box, sent, page, NOW)
            batches.getAll(listOf(Long.MAX_VALUE))
            if (!sent) {
                blocks.blocked(7, Long.MAX_VALUE)
                blocks.blocked(7, Long.MAX_VALUE)
            }
        }
        confirmVerified(members, clock, notes, batches, blocks)
        confirmVerified(details, notifications, blockLists, counts)
        assertThat(SecurityContextHolder.getContext().authentication).isNull()
    }

    @Test
    fun `건수가 0이고 최대 페이지를 요청했을 때__목록을 조회하면__일괄 조회와 차단 확인 없이 빈 페이지를 준다`() {
        // given
        every { members.get(any()) } returns active(7)
        every { clock.instant() } returns NOW
        every { notes.count(any(), any(), any(), any()) } returns 0L
        every { notes.read(any(), any(), any(), any(), any()) } returns listOf()

        // when
        val result = mvc.perform(authenticated(PATH).param("page", "10000"))

        // then
        result
            .andExpect(status().isOk())
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(header().doesNotExist("Set-Cookie"))
            .andExpect(content().json(page(0, 0, 10000).toString(), JsonCompareMode.STRICT))
        verifySequence {
            members.get(7)
            clock.instant()
            notes.count(7, "INBOX", false, NOW)
            notes.read(7, "INBOX", false, 10000, NOW)
        }
        confirmVerified(members, clock, notes)
        confirmVerified(batches, blocks, details, notifications, blockLists, counts)
    }

    @ParameterizedTest
    @MethodSource("businessRejections")
    fun `페이지와 쪽지함이 모두 틀렸을 때__목록을 조회하면__페이지를 먼저 검사하고 의존성을 부르지 않는다`(
        page: String,
        box: String,
        message: String,
    ) {
        // given
        val request = authenticated(PATH).param("page", page).param("box", box)

        // when
        val result = mvc.perform(request)

        // then
        result
            .andExpect(status().isBadRequest())
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(header().doesNotExist("Set-Cookie"))
            .andExpect(content().json(mapper.writeValueAsString(mapOf("message" to message)), JsonCompareMode.STRICT))
        noDependencies()
    }

    @ParameterizedTest
    @MethodSource("conversionRejections")
    fun `변환할 수 없는 즐겨찾기나 페이지가 주어졌을 때__목록을 조회하면__검증 전에 변환 400을 돌려준다`(
        favorites: String,
        page: String,
    ) {
        // given
        val request = authenticated(PATH).param("favorites", favorites).param("page", page).param("box", "WRONG")

        // when
        val result = mvc.perform(request)

        // then
        result
            .andExpect(status().isBadRequest())
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(header().doesNotExist("Set-Cookie"))
            .andExpect(content().json("{\"message\":\"요청 형식과 로그인 정보를 확인해 주세요.\"}", JsonCompareMode.STRICT))
        noDependencies()
    }

    @Test
    fun `현재 회원이 탈퇴 대기 상태일 때__목록을 조회하면__시계와 조회 없이 403으로 거부한다`() {
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
        confirmVerified(clock, notes, batches, blocks, details, notifications, blockLists, counts)
    }

    @ParameterizedTest
    @MethodSource("dependencyFailures")
    fun `회원 시계 건수 목록 일괄 조회 또는 차단 확인이 실패했을 때__목록을 조회하면__원인별 503을 주고 이후 호출을 멈춘다`(
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
                if (phase == "COUNT") {
                    every { notes.count(any(), any(), any(), any()) } throws failure
                } else {
                    every { notes.count(any(), any(), any(), any()) } returns 1L
                    if (phase == "BATCH" || phase == "BLOCK") {
                        every { notes.read(any(), any(), any(), any(), any()) } returns
                            listOf(row(FIRST, 8L, 7L, "INBOX", null, false, null, null))
                        if (phase == "BATCH") {
                            every { batches.getAll(any()) } throws failure
                        } else {
                            every { batches.getAll(any()) } returns listOf(active(8))
                            every { blocks.blocked(any(), any()) } throws failure
                        }
                    } else {
                        every { notes.read(any(), any(), any(), any(), any()) } throws failure
                    }
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
        verifySequence {
            members.get(7)
            if (phase != "CURRENT") {
                clock.instant()
            }
            if (phase != "CURRENT" && phase != "CLOCK") {
                notes.count(7, "INBOX", false, NOW)
            }
            if (!listOf("CURRENT", "CLOCK", "COUNT").contains(phase)) {
                notes.read(7, "INBOX", false, 0, NOW)
            }
            if (phase == "BATCH" || phase == "BLOCK") {
                batches.getAll(listOf(8L))
            }
            if (phase == "BLOCK") {
                blocks.blocked(7, 8)
            }
        }
        confirmVerified(members, clock, notes, batches, blocks)
        confirmVerified(details, notifications, blockLists, counts)
        assertThat(SecurityContextHolder.getContext().authentication).isNull()
    }

    @Test
    fun `기존 알림 상세 차단 목록 경로가 있을 때__각각 읽으면__목록 밖에서 성공과 오류와 범위 한정 조언을 유지한다`() {
        // given
        every { members.get(any()) } returns active(7)
        every { clock.instant() } returns NOW
        every { notifications.read(any(), any(), any()) } returns listOf()
        every { notifications.unread(any(), any()) } returns 0L
        every { details.find(any(), any(), any()) } returns Optional.empty()

        // when
        val notificationResult = mvc.perform(authenticated("$PATH/notifications"))
        val detailResult = mvc.perform(authenticated("$PATH/$FIRST"))
        val blockResult = mvc.perform(authenticated("$PATH/blocks").param("page", "-1"))

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
        detailResult
            .andExpect(status().isNotFound())
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(header().doesNotExist("Set-Cookie"))
            .andExpect(content().json("{\"message\":\"쪽지를 찾을 수 없습니다.\"}", JsonCompareMode.STRICT))
        blockResult
            .andExpect(status().isBadRequest())
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(header().doesNotExist("Set-Cookie"))
            .andExpect(content().json("{\"message\":\"페이지 범위를 확인해 주세요.\"}", JsonCompareMode.STRICT))
        verify(exactly = 2) { members.get(7) }
        verify(exactly = 2) { clock.instant() }
        verify(exactly = 1) { notifications.read(7, null, NOW) }
        verify(exactly = 1) { notifications.unread(7, NOW) }
        verify(exactly = 1) { details.find(7, FIRST, NOW) }
        confirmVerified(members, clock, notifications, details)
        confirmVerified(notes, batches, blocks, blockLists, counts)
        assertThat(SecurityContextHolder.getContext().authentication).isNull()
    }

    @Test
    fun `관리자 건수 경로에 일반 권한과 런타임 오류가 있을 때__요청하면__목록 조언 밖에서 403과 공통 500을 유지한다`() {
        // given
        every { counts.count() } throws IllegalStateException("synthetic-admin-count")

        // when
        val forbidden = mvc.perform(authenticated("/api/v1/admin/users/stats/total-users"))
        val failed = mvc.perform(
            get("/api/v1/admin/users/stats/total-users")
                .header("Authorization", "Bearer " + F3TestInputs.token(7, "ROLE_ADMIN")),
        )

        // then
        forbidden
            .andExpect(status().isForbidden())
            .andExpect(header().doesNotExist("Set-Cookie"))
            .andExpect(
                content().json("{\"code\":403,\"message\":\"관리자 권한이 필요합니다.\",\"data\":null}", JsonCompareMode.STRICT),
            )
        failed
            .andExpect(status().isInternalServerError())
            .andExpect(header().doesNotExist("Set-Cookie"))
            .andExpect(content().json("{\"code\":500,\"message\":\"서버 에러!\",\"data\":null}", JsonCompareMode.STRICT))
        verify(exactly = 1) { counts.count() }
        confirmVerified(counts)
        confirmVerified(members, batches, clock, notes, blocks, details, notifications, blockLists)
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
        confirmVerified(members, batches, notes, blocks, clock, details, notifications, blockLists, counts)
    }

    companion object {
        private const val PATH = "/api/v1/notes"
        private val FIRST = UUID(23L, 1L)
        private val SECOND = UUID(23L, 2L)
        private val THIRD = UUID(23L, 3L)
        private val NOW: Instant = Instant.parse("2031-01-01T00:00:00.999999Z")
        private val CREATED: Instant = Instant.parse("2030-12-31T15:00:00.123456Z")
        private const val BODY = "  원문\n<태그>  "

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
