package com.pawbridge.backend

import com.pawbridge.backend.boot.configuration.AdminPostReadConfiguration
import com.pawbridge.backend.boot.configuration.ReadSecurityConfiguration
import com.pawbridge.backend.community.internal.post.application.PostPageReader
import com.pawbridge.backend.community.internal.post.application.ReadAdminPosts
import com.pawbridge.backend.community.internal.post.application.ReadPost
import com.pawbridge.backend.support.anyNonNull
import com.pawbridge.backend.user.internal.directory.application.UserDirectoryQuery
import com.pawbridge.backend.user.internal.directory.web.AdminUserDirectoryController
import com.pawbridge.backend.user.internal.profile.application.UserProfile
import com.pawbridge.backend.user.internal.profile.web.UserProfileExceptionHandler
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.isNull
import org.mockito.Mockito.`when` as whenever
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.verifyNoMoreInteractions
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.dao.DataAccessResourceFailureException
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.test.context.ContextConfiguration
import org.springframework.test.context.bean.override.mockito.MockitoBean
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

    @MockitoBean
    lateinit var users: UserDirectoryQuery

    @MockitoBean
    lateinit var posts: ReadAdminPosts

    @MockitoBean
    lateinit var post: ReadPost

    @MockitoBean
    lateinit var postPages: PostPageReader

    @ParameterizedTest
    @ValueSource(strings = ["", "/search"])
    fun given__an_admin_and_forged_identity__when__reading_default_pages__then__return_exact_source_page_and_seven_fields_without_password(
        suffix: String,
    ) {
        val row = UserProfile(
            Long.MAX_VALUE,
            "fixture@example.invalid",
            " 한글 ",
            null,
            "GOOGLE",
            "ROLE_USER",
            LocalDateTime.parse("2026-10-20T01:02:03.123456"),
        )
        whenever(users.page(isNull(), isNull(), anyNonNull<UserDirectoryQuery.Request>()))
            .thenReturn(UserDirectoryQuery.Slice(listOf(row), 41))
        mvc.perform(
            get(ROOT + suffix)
                .header("Authorization", admin())
                .header("X-User-Id", "999")
                .header("X-User-Role", "ROLE_USER"),
        )
            .andExpect(status().isOk())
            .andExpect(content().json(EXPECTED, JsonCompareMode.STRICT))
            .andExpect(header().string("Cache-Control", CACHE))
            .andExpect(header().doesNotExist("Set-Cookie"))
        verify(users).page(null, null, request(0, 20))
        verifyNoMoreInteractions(users)
        assertCleared()
    }

    @Test
    fun given__raw_keyword_role_and_repeated_sorts__when__searching__then__bind_exact_values_and_source_sort_order() {
        whenever(users.page(any<String>(), any<String>(), anyNonNull<UserDirectoryQuery.Request>()))
            .thenReturn(UserDirectoryQuery.Slice(listOf(), 0))
        mvc.perform(
            get("$ROOT/search")
                .param("keyword", " %_\\한글 ")
                .param("role", "ROLE_SHELTER")
                .param("sort", "name,email,desc,ignorecase", "userId,asc")
                .header("Authorization", admin()),
        ).andExpect(status().isOk())
        verify(users).page(
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

    @ParameterizedTest
    @CsvSource("bad,zero,0,20", "2147483647,3000,2147483647,2000")
    fun given__malformed_or_large_paging__when__binding__then__retain_existing_fallback_and_resource_cap(
        page: String,
        size: String,
        number: Int,
        cap: Int,
    ) {
        whenever(users.page(any<String>(), any<String>(), anyNonNull<UserDirectoryQuery.Request>()))
            .thenReturn(UserDirectoryQuery.Slice(listOf(), 0))
        mvc.perform(get(ROOT).param("page", page).param("size", size).header("Authorization", admin()))
            .andExpect(status().isOk())
        verify(users).page(null, null, request(number, cap))
    }

    @ParameterizedTest
    @ValueSource(strings = ["role_user", "UNKNOWN"])
    fun given__invalid_role__when__an_admin_binds_search__then__retain_original_runtime_500_without_query(role: String) {
        mvc.perform(get("$ROOT/search").param("role", role).header("Authorization", admin()))
            .andExpect(status().isInternalServerError())
            .andExpect(content().json(ERROR, JsonCompareMode.STRICT))
        verifyNoInteractions(users)
    }

    @ParameterizedTest
    @ValueSource(strings = ["missing", "invalid"])
    fun given__missing_or_invalid_bearer__when__search_role_is_invalid__then__authenticate_before_binding(mode: String) {
        val request = get("$ROOT/search").param("role", "UNKNOWN").header("X-User-Role", "ROLE_ADMIN")
        if (mode == "invalid") {
            request.header("Authorization", "Bearer invalid")
        }
        mvc.perform(request).andExpect(status().isUnauthorized()).andExpect(header().doesNotExist("Set-Cookie"))
        verifyNoInteractions(users)
        assertCleared()
    }

    @ParameterizedTest
    @ValueSource(strings = ["", "/search"])
    fun given__a_user_and_forged_admin_header__when__reading__then__deny_before_the_query(suffix: String) {
        mvc.perform(
            get(ROOT + suffix)
                .header("Authorization", "Bearer " + F3TestInputs.token(7, "ROLE_USER"))
                .header("X-User-Role", "ROLE_ADMIN"),
        )
            .andExpect(status().isForbidden())
            .andExpect(
                content().json("{\"code\":403,\"message\":\"관리자 권한이 필요합니다.\",\"data\":null}", JsonCompareMode.STRICT),
            )
        verifyNoInteractions(users)
        assertCleared()
    }

    @ParameterizedTest
    @ValueSource(strings = ["storage", "commit", "unsupported-sort"])
    fun given__query_commit_or_known_sort_rejection__when__listing__then__reuse_safe_source_generic_500(phase: String) {
        val failure: RuntimeException = when (phase) {
            "storage" -> DataAccessResourceFailureException("synthetic-private-password")
            "commit" -> TransactionSystemException("synthetic-private-password")
            else -> IllegalArgumentException("synthetic-private-password")
        }
        whenever(users.page(any<String>(), any<String>(), anyNonNull<UserDirectoryQuery.Request>())).thenThrow(failure)
        mvc.perform(
            get(ROOT)
                .param("sort", if (phase == "unsupported-sort") "password,asc" else "createdAt,desc")
                .header("Authorization", admin()),
        )
            .andExpect(status().isInternalServerError())
            .andExpect(content().json(ERROR, JsonCompareMode.STRICT))
            .andExpect(header().string("Cache-Control", CACHE))
            .andExpect(header().doesNotExist("Set-Cookie"))
        assertCleared()
    }

    @Test
    fun given__neighboring_paths_or_write_methods__when__an_admin_requests_them__then__keep_default_deny() {
        mvc.perform(get("$ROOT/search/extra").header("Authorization", admin())).andExpect(status().isForbidden())
        mvc.perform(post(ROOT).header("Authorization", admin())).andExpect(status().isForbidden())
        verifyNoInteractions(users)
    }

    companion object {
        private const val ROOT = "/api/v1/admin/users"
        private const val CACHE = "no-cache, no-store, max-age=0, must-revalidate"
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
