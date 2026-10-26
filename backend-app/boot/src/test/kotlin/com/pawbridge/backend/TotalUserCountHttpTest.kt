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
import com.pawbridge.backend.user.api.ContactMemberQuery
import com.pawbridge.backend.user.internal.statistics.application.TotalUserCountQuery
import com.pawbridge.backend.user.internal.statistics.web.TotalUserCountController
import com.pawbridge.backend.user.internal.statistics.web.TotalUserCountExceptionHandler
import io.mockk.confirmVerified
import io.mockk.every
import io.mockk.verify
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
            .andExpect(header().doesNotExist("Set-Cookie"))
            .andExpect(content().json("{\"code\":500,\"message\":\"서버 에러!\",\"data\":null}", JsonCompareMode.STRICT))
        verify(exactly = 1) { counts.count() }
        confirmVerified(counts)
        confirmVerified(members, blocks, readPost, listPosts)
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
