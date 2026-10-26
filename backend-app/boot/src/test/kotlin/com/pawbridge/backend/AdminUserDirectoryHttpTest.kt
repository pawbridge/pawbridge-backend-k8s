package com.pawbridge.backend

import com.ninjasquad.springmockk.MockkBean
import com.pawbridge.backend.boot.configuration.AdminPostReadConfiguration
import com.pawbridge.backend.boot.configuration.ReadSecurityConfiguration
import com.pawbridge.backend.community.internal.post.application.PostPageReader
import com.pawbridge.backend.community.internal.post.application.ReadAdminPosts
import com.pawbridge.backend.community.internal.post.application.ReadPost
import com.pawbridge.backend.user.internal.directory.application.UserDirectoryQuery
import com.pawbridge.backend.user.internal.directory.web.AdminUserDirectoryController
import com.pawbridge.backend.user.internal.profile.application.UserProfile
import com.pawbridge.backend.user.internal.profile.web.UserProfileExceptionHandler
import io.mockk.confirmVerified
import io.mockk.every
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.dao.DataAccessResourceFailureException
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.test.context.ContextConfiguration
import org.springframework.test.json.JsonCompareMode
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.transaction.TransactionSystemException
import java.time.LocalDateTime

@WebMvcTest(
    controllers = [AdminUserDirectoryController::class],
    excludeAutoConfiguration = [UserDetailsServiceAutoConfiguration::class],
)
@ContextConfiguration(
    classes = [
        AdminUserDirectoryController::class,
        UserProfileExceptionHandler::class,
        AdminPostReadConfiguration::class,
        ReadSecurityConfiguration::class,
        F3TestInputs.SecurityInputs::class,
    ],
)
class AdminUserDirectoryHttpTest {
    @Autowired
    lateinit var mvc: MockMvc

    @MockkBean
    lateinit var users: UserDirectoryQuery

    @MockkBean
    lateinit var posts: ReadAdminPosts

    @MockkBean
    lateinit var post: ReadPost

    @MockkBean
    lateinit var postPages: PostPageReader

    @ParameterizedTest
    @ValueSource(strings = ["", "/search"])
    fun `관리자와 위조 신원__기본 페이지를 조회하면__원본 페이지와 비밀번호 없는 일곱 필드를 그대로 돌려준다`(
        suffix: String,
    ) {
        // given
        val row = UserProfile(
            Long.MAX_VALUE,
            "fixture@example.invalid",
            " 한글 ",
            null,
            "GOOGLE",
            "ROLE_USER",
            LocalDateTime.parse("2026-10-20T01:02:03.123456"),
        )

        every { users.page(any(), any(), any()) } returns UserDirectoryQuery.Slice(listOf(row), 41)

        // when
        val result = mvc.perform(
            get(ROOT + suffix)
                .header("Authorization", admin())
                .header("X-User-Id", "999")
                .header("X-User-Role", "ROLE_USER"),
        )

        // then
        result
            .andExpect(status().isOk())
            .andExpect(content().json(EXPECTED, JsonCompareMode.STRICT))
            .andExpect(header().string("Cache-Control", CACHE))
            .andExpect(header().doesNotExist("Set-Cookie"))
        verify(exactly = 1) { users.page(null, null, request(0, 20)) }
        confirmVerified(users)
        assertCleared()
    }

    @Test
    fun `원문 키워드 역할 반복 정렬__검색하면__정확한 값과 원본 정렬 순서로 바인딩한다`() {
        // given
        every { users.page(any(), any(), any()) } returns UserDirectoryQuery.Slice(listOf(), 0)

        // when
        val result = mvc.perform(
            get("$ROOT/search")
                .param("keyword", " %_\\한글 ")
                .param("role", "ROLE_SHELTER")
                .param("sort", "name,email,desc,ignorecase", "userId,asc")
                .header("Authorization", admin()),
        )

        // then
        result.andExpect(status().isOk())
        verify(exactly = 1) {
            users.page(
                " %_\\한글 ",
                "ROLE_SHELTER",
                UserDirectoryQuery.Request(
                    0,
                    20,
                    listOf(
                        UserDirectoryQuery.Order("name", false, true),
                        UserDirectoryQuery.Order("email", false, true),
                        UserDirectoryQuery.Order("userId", true, false),
                    ),
                ),
            )
        }
    }

    @ParameterizedTest
    @CsvSource("bad,zero,0,20", "2147483647,3000,2147483647,2000")
    fun `잘못되었거나 큰 페이징__바인딩하면__기존 대체값과 자원 상한을 유지한다`(
        page: String,
        size: String,
        number: Int,
        cap: Int,
    ) {
        // given
        every { users.page(any(), any(), any()) } returns UserDirectoryQuery.Slice(listOf(), 0)

        // when
        val result = mvc.perform(get(ROOT).param("page", page).param("size", size).header("Authorization", admin()))

        // then
        result.andExpect(status().isOk())
        verify(exactly = 1) { users.page(null, null, request(number, cap)) }
    }

    @ParameterizedTest
    @ValueSource(strings = ["role_user", "UNKNOWN"])
    fun `잘못된 역할__관리자가 검색을 바인딩하면__조회 없이 400을 응답한다`(role: String) {
        // given
        val request = get("$ROOT/search").param("role", role).header("Authorization", admin())

        // when
        val result = mvc.perform(request)

        // then
        result
            .andExpect(status().isBadRequest())
            .andExpect(content().json(INVALID_VALUE, JsonCompareMode.STRICT))
        confirmVerified(users)
    }

    @ParameterizedTest
    @ValueSource(strings = ["missing", "invalid"])
    fun `없거나 잘못된 Bearer__검색 역할이 잘못되면__바인딩 전에 인증한다`(mode: String) {
        // given
        val request = get("$ROOT/search").param("role", "UNKNOWN").header("X-User-Role", "ROLE_ADMIN")
        if (mode == "invalid") {
            request.header("Authorization", "Bearer invalid")
        }

        // when
        val result = mvc.perform(request)

        // then
        result.andExpect(status().isUnauthorized()).andExpect(header().doesNotExist("Set-Cookie"))
        confirmVerified(users)
        assertCleared()
    }

    @ParameterizedTest
    @ValueSource(strings = ["", "/search"])
    fun `사용자와 위조 관리자 헤더__조회하면__질의 전에 거부한다`(suffix: String) {
        // given
        val request = get(ROOT + suffix)
            .header("Authorization", "Bearer " + F3TestInputs.token(7, "ROLE_USER"))
            .header("X-User-Role", "ROLE_ADMIN")

        // when
        val result = mvc.perform(request)

        // then
        result
            .andExpect(status().isForbidden())
            .andExpect(
                content().json("{\"code\":403,\"message\":\"관리자 권한이 필요합니다.\",\"data\":null}", JsonCompareMode.STRICT),
            )
        confirmVerified(users)
        assertCleared()
    }

    @ParameterizedTest
    @ValueSource(strings = ["storage", "commit", "unsupported-sort"])
    fun `질의 커밋 또는 알려진 정렬 거부__목록을 조회하면__안전한 원본 일반 500을 재사용한다`(phase: String) {
        // given
        val failure: RuntimeException = when (phase) {
            "storage" -> DataAccessResourceFailureException("synthetic-private-password")
            "commit" -> TransactionSystemException("synthetic-private-password")
            else -> IllegalArgumentException("synthetic-private-password")
        }

        every { users.page(any(), any(), any()) } throws failure

        // when
        val result = mvc.perform(
            get(ROOT)
                .param("sort", if (phase == "unsupported-sort") "password,asc" else "createdAt,desc")
                .header("Authorization", admin()),
        )

        // then
        result
            .andExpect(status().isInternalServerError())
            .andExpect(content().json(ERROR, JsonCompareMode.STRICT))
            .andExpect(header().string("Cache-Control", CACHE))
            .andExpect(header().doesNotExist("Set-Cookie"))
        assertCleared()
    }

    @Test
    fun `이웃 경로나 쓰기 메서드__관리자가 요청하면__기본 거부를 유지한다`() {
        // given
        val token = admin()

        // when
        val neighbor = mvc.perform(get("$ROOT/search/extra").header("Authorization", token))
        val write = mvc.perform(post(ROOT).header("Authorization", token))

        // then
        neighbor.andExpect(status().isForbidden())
        write.andExpect(status().isForbidden())
        confirmVerified(users)
    }

    companion object {
        private const val ROOT = "/api/v1/admin/users"
        private const val CACHE = "no-cache, no-store, max-age=0, must-revalidate"
        private const val INVALID_VALUE = "{\"code\":400,\"message\":\"요청 값의 형식이 올바르지 않습니다.\",\"data\":null}"
        private const val ERROR = "{\"code\":500,\"message\":\"서버 에러!\",\"data\":null}"
        private const val EXPECTED = """
        {"code":200,"message":"요청이 성공적으로 처리되었습니다.","data":{
          "content":[{"userId":9223372036854775807,"email":"fixture@example.invalid","name":" 한글 ","nickname":null,"provider":"GOOGLE","role":"ROLE_USER","createdAt":"2026-10-20T01:02:03.123456"}],
          "pageable":{"pageNumber":0,"pageSize":20,"sort":{"sorted":true,"unsorted":false,"empty":false},"offset":0,"paged":true,"unpaged":false},
          "last":false,"totalElements":41,"totalPages":3,"numberOfElements":1,"first":true,"size":20,"number":0,
          "sort":{"sorted":true,"unsorted":false,"empty":false},"empty":false}}
        """

        private fun assertCleared() {
            assertThat(SecurityContextHolder.getContext().authentication).isNull()
        }

        private fun admin(): String = "Bearer " + F3TestInputs.token(7, "ROLE_ADMIN")

        private fun request(page: Int, size: Int): UserDirectoryQuery.Request {
            return UserDirectoryQuery.Request(page, size, listOf(UserDirectoryQuery.Order("createdAt", false, false)))
        }
    }
}
