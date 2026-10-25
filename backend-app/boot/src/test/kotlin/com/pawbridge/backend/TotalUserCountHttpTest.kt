package com.pawbridge.backend

import com.fasterxml.jackson.databind.ObjectMapper
import com.ninjasquad.springmockk.MockkBean
import com.pawbridge.backend.boot.configuration.ReadSecurityConfiguration
import com.pawbridge.backend.community.internal.note.application.NoteBlockReader
import com.pawbridge.backend.community.internal.note.application.ReadNoteRecipient
import com.pawbridge.backend.community.internal.note.web.NoteRecipientController
import com.pawbridge.backend.community.internal.note.web.NoteRecipientExceptionHandler
import com.pawbridge.backend.community.internal.post.application.ListPosts
import com.pawbridge.backend.community.internal.post.application.ReadPost
import com.pawbridge.backend.community.internal.post.web.PostReadController
import com.pawbridge.backend.community.internal.post.web.PostReadExceptionHandler
import com.pawbridge.backend.user.api.ContactMember
import com.pawbridge.backend.user.api.ContactMemberQuery
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
import java.sql.SQLException
import java.util.stream.Stream

@WebMvcTest(
    controllers = [TotalUserCountController::class],
    excludeAutoConfiguration = [UserDetailsServiceAutoConfiguration::class],
)
@ContextConfiguration(
    classes = [
        TotalUserCountController::class,
        TotalUserCountExceptionHandler::class,
        NoteRecipientController::class,
        NoteRecipientExceptionHandler::class,
        ReadNoteRecipient::class,
        PostReadController::class,
        PostReadExceptionHandler::class,
        ReadSecurityConfiguration::class,
        F3TestInputs.SecurityInputs::class,
    ],
)
class TotalUserCountHttpTest {
    @Autowired
    lateinit var mvc: MockMvc

    @Autowired
    lateinit var mapper: ObjectMapper

    @MockkBean
    lateinit var counts: TotalUserCountQuery

    @MockkBean
    lateinit var members: ContactMemberQuery

    @MockkBean
    lateinit var blocks: NoteBlockReader

    @MockkBean
    lateinit var readPost: ReadPost

    @MockkBean
    lateinit var listPosts: ListPosts

    @ParameterizedTest
    @ValueSource(longs = [0, Long.MAX_VALUE])
    fun `검증된 관리자이고 회원 상태 조회가 없을 때__집계하면__정확한 봉투와 원시 long을 돌려준다`(
        count: Long,
    ) {
        // given
        val request = countWithRole("ROLE_ADMIN")
            .header("X-User-Id", "0")
            .header("X-User-Role", "ROLE_USER")
            .param("userId", "0")
            .param("role", "ROLE_USER")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"userId\":0,\"role\":\"ROLE_USER\"}")

        every { counts.count() } returns count

        // when
        val result = mvc.perform(request)

        // then
        val response = result
            .andExpect(status().isOk())
            .andExpect(header().string("Cache-Control", SECURITY_CACHE))
            .andExpect(header().doesNotExist("Set-Cookie"))
            .andExpect(
                content().json(
                    "{\"code\":200,\"message\":\"요청이 성공적으로 처리되었습니다.\",\"data\":" + count + "}",
                    JsonCompareMode.STRICT,
                ),
            )
            .andReturn().response
        val rawCount = mapper.readTree(response.contentAsString).path("data")
        assertThat(rawCount.isIntegralNumber).isTrue()
        assertThat(rawCount.longValue()).isEqualTo(count)
        verify(exactly = 1) { counts.count() }
        confirmVerified(counts)
        confirmVerified(members, blocks, readPost, listPosts)
        assertThat(SecurityContextHolder.getContext().authentication).isNull()
    }

    @ParameterizedTest
    @ValueSource(strings = ["ROLE_USER", "ROLE_SHELTER", "", " ", "ROLE_UNKNOWN", "ROLE_ADMIN "])
    fun `관리자가 아닌 서명 역할과 위조 관리자 신원일 때__집계하면__질의 전에 이 경로의 관리자 403만 돌려준다`(
        role: String,
    ) {
        // given
        val request = countWithRole(role)
            .header("X-User-Id", "1")
            .header("X-User-Role", "ROLE_ADMIN")
            .param("userId", "1")
            .param("role", "ROLE_ADMIN")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"userId\":1,\"role\":\"ROLE_ADMIN\"}")

        // when
        val result = mvc.perform(request)

        // then
        result
            .andExpect(status().isForbidden())
            .andExpect(header().string("Cache-Control", SECURITY_CACHE))
            .andExpect(header().doesNotExist("Set-Cookie"))
            .andExpect(
                content().json("{\"code\":403,\"message\":\"관리자 권한이 필요합니다.\",\"data\":null}", JsonCompareMode.STRICT),
            )
        confirmVerified(counts, members, blocks, readPost, listPosts)
        assertThat(SecurityContextHolder.getContext().authentication).isNull()
    }

    @ParameterizedTest
    @MethodSource("rejectedAuthorizations")
    fun `토큰이 없거나 잘못되고 위조 관리자 헤더가 있을 때__집계하면__질의 전에 기존 401을 돌려준다`(
        authorization: String?,
        message: String,
    ) {
        // given
        val request = get(PATH).header("X-User-Id", "1").header("X-User-Role", "ROLE_ADMIN")
        if (authorization != null) {
            request.header("Authorization", authorization)
        }

        // when
        val result = mvc.perform(request)

        // then
        result
            .andExpect(status().isUnauthorized())
            .andExpect(header().string("Cache-Control", SECURITY_CACHE))
            .andExpect(header().doesNotExist("Set-Cookie"))
            .andExpect(
                content().json(
                    mapper.writeValueAsString(TotalUserCountController.Response<Any?>(401, message, null)),
                    JsonCompareMode.STRICT,
                ),
            )
        confirmVerified(counts, members, blocks, readPost, listPosts)
        assertThat(SecurityContextHolder.getContext().authentication).isNull()
    }

    @ParameterizedTest
    @MethodSource("countFailures")
    fun `관리자 인가 뒤 집계 의존성이 실패했을 때__집계하면__인증을 재분류하지 않고 안전한 공통 500을 돌려준다`(
        failure: RuntimeException,
    ) {
        // given
        every { counts.count() } throws failure

        // when
        val result = mvc.perform(countWithRole("ROLE_ADMIN"))

        // then
        result
            .andExpect(status().isInternalServerError())
            .andExpect(header().string("Cache-Control", SECURITY_CACHE))
            .andExpect(header().doesNotExist("Set-Cookie"))
            .andExpect(content().json("{\"code\":500,\"message\":\"서버 에러!\",\"data\":null}", JsonCompareMode.STRICT))
        verify(exactly = 1) { counts.count() }
        confirmVerified(counts)
        confirmVerified(members, blocks, readPost, listPosts)
        assertThat(SecurityContextHolder.getContext().authentication).isNull()
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "HEAD /api/v1/admin/users/stats/total-users",
            "OPTIONS /api/v1/admin/users/stats/total-users",
            "POST /api/v1/admin/users/stats/total-users",
            "PUT /api/v1/admin/users/stats/total-users",
            "DELETE /api/v1/admin/users/stats/total-users",
            "GET /api/v1/admin/users/stats/total-users/",
            "GET /api/admin/users/stats/total-users",
            "GET /api/v1/admin/users/extra/extra",
            "GET /api/v1/admin/users/stats/active-users",
            "DELETE /api/v1/admin/users/8/extra",
        ],
    )
    fun `노출되지 않은 관리자 이웃 경로나 메서드일 때__유효한 관리자 토큰으로 요청하면__질의 전에 일반 403을 유지한다`(
        request: String,
    ) {
        // given
        val parts = request.split(" ", limit = 2)
        val builder = request(HttpMethod.valueOf(parts[0]), parts[1])
            .header("Authorization", "Bearer " + F3TestInputs.token(7, "ROLE_ADMIN"))

        // when
        val result = mvc.perform(builder)

        // then
        result
            .andExpect(status().isForbidden())
            .andExpect(header().string("Cache-Control", SECURITY_CACHE))
            .andExpect(header().doesNotExist("Set-Cookie"))
            .andExpect(
                content().json("{\"code\":403,\"message\":\"접근할 수 없습니다.\",\"data\":null}", JsonCompareMode.STRICT),
            )
        confirmVerified(counts, members, blocks, readPost, listPosts)
        assertThat(SecurityContextHolder.getContext().authentication).isNull()
    }

    @ParameterizedTest
    @ValueSource(strings = ["ROLE_ADMIN", "", " ", "ROLE_UNKNOWN"])
    fun `기존 쪽지 경로에 관리자와 빈 또는 알 수 없는 역할이 있을 때__수신자를 읽으면__long 주체와 기존 인증 정책을 유지한다`(
        role: String,
    ) {
        // given
        val request = get("/api/v1/notes/recipients/8")
            .header("Authorization", "Bearer " + F3TestInputs.token(7, role))

        every { members.get(7) } returns ContactMember(7L, "본인", true, false)
        every { members.get(8) } returns ContactMember(8L, "상대", true, false)
        every { blocks.blocked(any(), any()) } returns false

        // when
        val result = mvc.perform(request)

        // then
        result
            .andExpect(status().isOk())
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(header().doesNotExist("Set-Cookie"))
        verifySequence {
            members.get(7)
            blocks.blocked(7, 8)
            members.get(8)
        }
        confirmVerified(members, blocks)
        confirmVerified(counts, readPost, listPosts)
        assertThat(SecurityContextHolder.getContext().authentication).isNull()
    }

    @Test
    fun `잘못된 베어러로 공개 게시글을 읽을 때__자체 실패가 나면__공개 접근과 범위 한정 500을 유지한다`() {
        // given
        every { readPost.read(any()) } throws IllegalStateException("synthetic-private-post")

        // when
        val result = mvc.perform(get("/api/v1/posts/read/42").header("Authorization", "Bearer invalid"))

        // then
        result
            .andExpect(status().isInternalServerError())
            .andExpect(content().json("{\"code\":500,\"message\":\"서버 에러!\",\"data\":null}", JsonCompareMode.STRICT))
        verify(exactly = 1) { readPost.read(42) }
        confirmVerified(readPost)
        confirmVerified(counts, members, blocks, listPosts)
        assertThat(SecurityContextHolder.getContext().authentication).isNull()
    }

    private fun countWithRole(role: String): MockHttpServletRequestBuilder {
        return get(PATH).header("Authorization", "Bearer " + F3TestInputs.token(Long.MAX_VALUE, role))
    }

    companion object {
        private const val PATH = "/api/v1/admin/users/stats/total-users"
        private const val SECURITY_CACHE = "no-cache, no-store, max-age=0, must-revalidate"

        @JvmStatic
        fun rejectedAuthorizations(): Stream<Arguments> {
            return Stream.of(
                Arguments.of(null, "인증 토큰이 필요합니다."),
                Arguments.of("Bearer invalid", "유효하지 않거나 만료된 토큰입니다."),
            )
        }

        @JvmStatic
        fun countFailures(): Stream<Arguments> {
            return Stream.of(
                Arguments.of(
                    DataAccessResourceFailureException("synthetic-private-query", SQLException("synthetic-private-password")),
                ),
                Arguments.of(IllegalStateException("Bearer synthetic-private-token")),
            )
        }
    }
}
