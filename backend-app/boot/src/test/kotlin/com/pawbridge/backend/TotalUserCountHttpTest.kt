package com.pawbridge.backend

import com.fasterxml.jackson.databind.ObjectMapper
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
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import org.junit.jupiter.params.provider.ValueSource
import org.mockito.Mockito.inOrder
import org.mockito.Mockito.`when` as whenever
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

    @MockitoBean
    lateinit var counts: TotalUserCountQuery

    @MockitoBean
    lateinit var members: ContactMemberQuery

    @MockitoBean
    lateinit var blocks: NoteBlockReader

    @MockitoBean
    lateinit var readPost: ReadPost

    @MockitoBean
    lateinit var listPosts: ListPosts

    @ParameterizedTest
    @ValueSource(longs = [0, Long.MAX_VALUE])
    fun given__verified_admin_without_member_state_lookup__when__counting__then__return_the_exact_envelope_and_raw_long(
        count: Long,
    ) {
        whenever(counts.count()).thenReturn(count)

        val response = mvc.perform(
            countWithRole("ROLE_ADMIN")
                .header("X-User-Id", "0")
                .header("X-User-Role", "ROLE_USER")
                .param("userId", "0")
                .param("role", "ROLE_USER")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"userId\":0,\"role\":\"ROLE_USER\"}"),
        )
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
        verify(counts).count()
        verifyNoMoreInteractions(counts)
        verifyNoInteractions(members, blocks, readPost, listPosts)
        assertThat(SecurityContextHolder.getContext().authentication).isNull()
    }

    @ParameterizedTest
    @ValueSource(strings = ["ROLE_USER", "ROLE_SHELTER", "", " ", "ROLE_UNKNOWN", "ROLE_ADMIN "])
    fun given__nonadmin_signed_role_and_spoofed_admin_identity__when__counting__then__return_only_this_routes_admin_403_before_queries(
        role: String,
    ) {
        mvc.perform(
            countWithRole(role)
                .header("X-User-Id", "1")
                .header("X-User-Role", "ROLE_ADMIN")
                .param("userId", "1")
                .param("role", "ROLE_ADMIN")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"userId\":1,\"role\":\"ROLE_ADMIN\"}"),
        )
            .andExpect(status().isForbidden())
            .andExpect(header().string("Cache-Control", SECURITY_CACHE))
            .andExpect(header().doesNotExist("Set-Cookie"))
            .andExpect(
                content().json("{\"code\":403,\"message\":\"관리자 권한이 필요합니다.\",\"data\":null}", JsonCompareMode.STRICT),
            )

        verifyNoInteractions(counts, members, blocks, readPost, listPosts)
        assertThat(SecurityContextHolder.getContext().authentication).isNull()
    }

    @ParameterizedTest
    @MethodSource("rejectedAuthorizations")
    fun given__missing_or_invalid_bearer_with_spoofed_admin_header__when__counting__then__return_original_401_before_queries(
        authorization: String?,
        message: String,
    ) {
        val request = get(PATH).header("X-User-Id", "1").header("X-User-Role", "ROLE_ADMIN")
        if (authorization != null) {
            request.header("Authorization", authorization)
        }

        mvc.perform(request)
            .andExpect(status().isUnauthorized())
            .andExpect(header().string("Cache-Control", SECURITY_CACHE))
            .andExpect(header().doesNotExist("Set-Cookie"))
            .andExpect(
                content().json(
                    mapper.writeValueAsString(TotalUserCountController.Response<Any?>(401, message, null)),
                    JsonCompareMode.STRICT,
                ),
            )

        verifyNoInteractions(counts, members, blocks, readPost, listPosts)
        assertThat(SecurityContextHolder.getContext().authentication).isNull()
    }

    @ParameterizedTest
    @MethodSource("countFailures")
    fun given__count_dependency_failure_after_admin_authorization__when__counting__then__return_common_safe_500_without_reclassifying_authentication(
        failure: RuntimeException,
    ) {
        whenever(counts.count()).thenThrow(failure)

        mvc.perform(countWithRole("ROLE_ADMIN"))
            .andExpect(status().isInternalServerError())
            .andExpect(header().string("Cache-Control", SECURITY_CACHE))
            .andExpect(header().doesNotExist("Set-Cookie"))
            .andExpect(content().json("{\"code\":500,\"message\":\"서버 에러!\",\"data\":null}", JsonCompareMode.STRICT))

        verify(counts).count()
        verifyNoMoreInteractions(counts)
        verifyNoInteractions(members, blocks, readPost, listPosts)
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
    fun given__unexposed_admin_neighbor_or_method__when__requesting_with_a_valid_admin_token__then__retain_generic_403_before_queries(
        request: String,
    ) {
        val parts = request.split(" ", limit = 2)

        mvc.perform(
            request(HttpMethod.valueOf(parts[0]), parts[1])
                .header("Authorization", "Bearer " + F3TestInputs.token(7, "ROLE_ADMIN")),
        )
            .andExpect(status().isForbidden())
            .andExpect(header().string("Cache-Control", SECURITY_CACHE))
            .andExpect(header().doesNotExist("Set-Cookie"))
            .andExpect(
                content().json("{\"code\":403,\"message\":\"접근할 수 없습니다.\",\"data\":null}", JsonCompareMode.STRICT),
            )

        verifyNoInteractions(counts, members, blocks, readPost, listPosts)
        assertThat(SecurityContextHolder.getContext().authentication).isNull()
    }

    @ParameterizedTest
    @ValueSource(strings = ["ROLE_ADMIN", "", " ", "ROLE_UNKNOWN"])
    fun given__admin_blank_or_unknown_role_on_existing_notes__when__reading_recipient__then__retain_long_principal_and_existing_authenticated_policy(
        role: String,
    ) {
        whenever(members.get(7)).thenReturn(ContactMember(7L, "본인", true, false))
        whenever(members.get(8)).thenReturn(ContactMember(8L, "상대", true, false))

        mvc.perform(get("/api/v1/notes/recipients/8").header("Authorization", "Bearer " + F3TestInputs.token(7, role)))
            .andExpect(status().isOk())
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(header().doesNotExist("Set-Cookie"))

        val order = inOrder(members, blocks)
        order.verify(members).get(7)
        order.verify(blocks).blocked(7, 8)
        order.verify(members).get(8)
        verifyNoMoreInteractions(members, blocks)
        verifyNoInteractions(counts, readPost, listPosts)
        assertThat(SecurityContextHolder.getContext().authentication).isNull()
    }

    @Test
    fun given__public_post_read_with_bad_bearer__when__its_own_failure_occurs__then__retain_public_access_and_its_scoped_500() {
        whenever(readPost.read(42)).thenThrow(IllegalStateException("synthetic-private-post"))

        mvc.perform(get("/api/v1/posts/read/42").header("Authorization", "Bearer invalid"))
            .andExpect(status().isInternalServerError())
            .andExpect(content().json("{\"code\":500,\"message\":\"서버 에러!\",\"data\":null}", JsonCompareMode.STRICT))

        verify(readPost).read(42)
        verifyNoMoreInteractions(readPost)
        verifyNoInteractions(counts, members, blocks, listPosts)
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
