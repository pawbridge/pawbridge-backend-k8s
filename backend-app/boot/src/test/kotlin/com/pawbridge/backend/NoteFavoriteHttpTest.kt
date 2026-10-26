package com.pawbridge.backend

import com.ninjasquad.springmockk.MockkBean
import com.pawbridge.backend.boot.configuration.ReadSecurityConfiguration
import com.pawbridge.backend.community.internal.note.application.NoteFavoriteMutation
import com.pawbridge.backend.community.internal.note.application.SetPrivateNoteFavorite
import com.pawbridge.backend.community.internal.note.web.NoteFavoriteController
import com.pawbridge.backend.community.internal.note.web.NoteFavoriteExceptionHandler
import com.pawbridge.backend.user.api.ContactMember
import com.pawbridge.backend.user.api.ContactMemberQuery
import com.pawbridge.backend.user.api.ContactMemberUnavailableException
import com.pawbridge.backend.user.internal.statistics.application.TotalUserCountQuery
import com.pawbridge.backend.user.internal.statistics.web.TotalUserCountController
import com.pawbridge.backend.user.internal.statistics.web.TotalUserCountExceptionHandler
import io.jsonwebtoken.Jwts
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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.transaction.TransactionSystemException
import java.time.Clock
import java.util.UUID
import java.util.stream.Stream

/** Real MVC/JWT and application callbacks; mocked mutation is not SQL, transaction or SSE evidence. */
@WebMvcTest(
    controllers = [NoteFavoriteController::class],
    excludeAutoConfiguration = [UserDetailsServiceAutoConfiguration::class],
)
@ContextConfiguration(
    classes = [
        NoteFavoriteController::class,
        NoteFavoriteExceptionHandler::class,
        SetPrivateNoteFavorite::class,
        TotalUserCountController::class,
        TotalUserCountExceptionHandler::class,
        ReadSecurityConfiguration::class,
        F3TestInputs.SecurityInputs::class,
    ],
)
class NoteFavoriteHttpTest {
    @Autowired
    lateinit var mvc: MockMvc

    @MockkBean
    lateinit var members: ContactMemberQuery

    @MockkBean
    lateinit var mutation: NoteFavoriteMutation

    @MockkBean(name = "readClock")
    lateinit var clock: Clock

    @MockkBean
    lateinit var counts: TotalUserCountQuery

    @ParameterizedTest
    @ValueSource(booleans = [true, false])
    fun `명시적 불리언과 서명된 호출자가 주어졌을 때__위조 헤더로 즐겨찾기를 지정하면__호출자 값을 유지하고 빈 200을 준다`(
        favorite: Boolean,
    ) {
        // given
        // General REST accepts a verified expiryless token; only exact stream requires expiry.
        val token = if (favorite) {
            F3TestInputs.token(7, "ROLE_USER")
        } else {
            Jwts.builder().claim("userId", 7L).claim("role", "ROLE_ADMIN").signWith(F3TestInputs.KEY).compact()
        }

        every { members.get(any()) } returns active()
        every { mutation.set(any(), any(), any(), any(), any()) } answers {
            arg<Runnable>(3).run()
        }

        // when
        val result = mvc.perform(
            put(PATH)
                .header("Authorization", "Bearer $token")
                .header("X-User-Id", "999")
                .header("X-User-Role", "ROLE_ADMIN")
                .header("X-Auth-Expires-At", Long.MAX_VALUE)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"favorite\":$favorite}"),
        )

        // then
        result
            .andExpect(status().isOk())
            .andExpect(content().string(""))
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(header().doesNotExist("Set-Cookie"))
        verify(exactly = 1) { mutation.set(7L, NOTE, favorite, any(), any()) }
        verify(exactly = 1) { members.get(7) }
        confirmVerified(mutation, members, clock, counts)
        cleared()
    }

    @Test
    fun `검증된 호출자와 깨진 UUID가 주어졌을 때__정식 불리언으로 지정하면__회원 확인과 변경 없이 범위 400을 준다`() {
        // given
        val request = put("/api/v1/notes/not-a-uuid/favorite")
            .header("Authorization", "Bearer " + F3TestInputs.token(7, "ROLE_USER"))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"favorite\":true}")

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

    @ParameterizedTest
    @MethodSource("failures")
    fun `현재 회원이나 변경이 실패했을 때__즐겨찾기를 지정하면__비활성 없음 회원 저장소 쓰기 정책을 유지한다`(
        kind: String,
        status: Int,
        message: String,
    ) {
        // given
        val failure: RuntimeException? = when (kind) {
            "inactive" -> null
            "missing" -> NoteFavoriteMutation.MissingOwnedNote()
            "member" -> ContactMemberUnavailableException(IllegalStateException("private-member"))
            "DAO" -> DataAccessResourceFailureException("private-SQL-body")
            else -> TransactionSystemException("private-commit-detail")
        }
        if (kind == "member") {
            every { members.get(any()) } throws failure!!
        } else {
            every { members.get(any()) } returns if (kind == "inactive") ContactMember(7L, "", false, true) else active()
        }

        every { mutation.set(any(), any(), any(), any(), any()) } answers {
            arg<Runnable>(3).run()
            if (failure != null) {
                throw failure
            }
        }

        // when
        val result = mvc.perform(
            put(PATH)
                .header("Authorization", "Bearer " + F3TestInputs.token(7, "ROLE_USER"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"favorite\":true}"),
        )

        // then
        result
            .andExpect(status().`is`(status))
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(header().doesNotExist("Set-Cookie"))
            .andExpect(content().json("{\"message\":\"" + message + "\"}", JsonCompareMode.STRICT))
        verify(exactly = 1) { mutation.set(7L, NOTE, true, any(), any()) }
        verify(exactly = 1) { members.get(7) }
        confirmVerified(mutation, members, clock, counts)
        cleared()
    }

    @Test
    fun `즐겨찾기 어드바이스 밖의 관리자 집계일 때__사용자나 위조 역할 관리자가 요청하면__403과 공통 500을 유지한다`() {
        // given
        every { counts.count() } throws IllegalStateException("private-count")

        // when
        val forbidden = mvc.perform(
            get(ADMIN_COUNT)
                .header("Authorization", "Bearer " + F3TestInputs.token(7, "ROLE_USER"))
                .header("X-User-Role", "ROLE_ADMIN"),
        )
        val failed = mvc.perform(get(ADMIN_COUNT).header("Authorization", "Bearer " + F3TestInputs.token(7, "ROLE_ADMIN")))

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
        confirmVerified(counts, members, mutation, clock)
        cleared()
    }

    private fun noDependencies() {
        confirmVerified(members, mutation, clock, counts)
        cleared()
    }

    companion object {
        private val NOTE: UUID = UUID.fromString("f2700000-0000-0000-0000-000000000001")
        private val PATH = "/api/v1/notes/" + NOTE + "/favorite"
        private const val ADMIN_COUNT = "/api/v1/admin/users/stats/total-users"

        @JvmStatic
        fun failures(): Stream<Arguments> {
            return Stream.of(
                Arguments.of("inactive", 403, "이용할 수 없는 회원입니다."),
                Arguments.of("missing", 404, "쪽지를 찾을 수 없습니다."),
                Arguments.of("member", 503, "회원 정보를 확인할 수 없습니다. 잠시 후 다시 시도해 주세요."),
                Arguments.of("DAO", 503, "쪽지 저장소를 사용할 수 없습니다. 잠시 후 다시 시도해 주세요."),
                Arguments.of("TX", 503, "쪽지를 처리하지 못했습니다. 잠시 후 다시 시도해 주세요."),
            )
        }

        private fun active(): ContactMember = ContactMember(7L, " 原文 ", true, false)

        private fun cleared() {
            assertThat(SecurityContextHolder.getContext().authentication).isNull()
        }
    }
}
