package com.pawbridge.backend

import com.pawbridge.backend.community.internal.note.application.ReadBlockedAuthors
import com.pawbridge.backend.community.internal.post.application.PostNotFoundException
import com.pawbridge.backend.community.internal.post.application.ReadPost
import com.pawbridge.backend.user.internal.profile.application.UserNotFoundException
import com.pawbridge.backend.user.internal.profile.application.UserProfileQuery
import com.pawbridge.backend.user.internal.statistics.application.TotalUserCountQuery
import io.mockk.clearAllMocks
import io.mockk.every
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import org.springframework.http.HttpMethod
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request
import org.springframework.transaction.TransactionSystemException

/**
 * Exception handlers are scoped to their own controllers. With every controller and handler assembled together,
 * a failure raised by one controller must still be answered by its own handler and nobody else's. One row per
 * failure replaces the copy each feature test kept to prove that the neighbouring handlers leave it alone.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AdviceScopeHttpTest {
    private val mvc: MockMvc = SecurityChainFixture.mvcWith(AllControllers::class.java)

    @BeforeEach
    fun forgetEarlierStubs() {
        clearAllMocks()
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("rows")
    fun `모든 컨트롤러와 어드바이스가 함께 있을 때__한 컨트롤러가 실패하면__자기 어드바이스의 응답만 돌려준다`(row: Row) {
        // given
        row.arrange()
        val request = request(HttpMethod.GET, row.path)
        row.query.forEach { (name, value) -> request.param(name, value) }
        request.header("Authorization", row.authorization)

        // when
        val result = mvc.perform(request).andReturn().response

        // then
        assertThat(result.status).describedAs("%s", row).isEqualTo(row.status)
        assertThat(result.contentAsString).describedAs("%s", row).isEqualTo(row.body)
        assertThat(result.getHeader("Cache-Control")).describedAs("%s", row).isEqualTo(row.cacheControl)
    }

    class Row(
        private val name: String,
        val path: String,
        val authorization: String,
        val status: Int,
        val body: String,
        val cacheControl: String = SecurityChainFixture.SECURITY_CACHE,
        val query: Map<String, String> = emptyMap(),
        val arrange: () -> Unit,
    ) {
        override fun toString(): String = name
    }

    companion object {
        private const val STORAGE = "쪽지 저장소를 사용할 수 없습니다. 잠시 후 다시 시도해 주세요."
        private val user = SecurityChainFixture.bearer(7, "ROLE_USER")
        private val admin = SecurityChainFixture.bearer(7, "ROLE_ADMIN")
        private const val INVALID = "Bearer invalid"

        @JvmStatic
        fun rows(): List<Row> = listOf(
            Row(
                "게시글 없음", "/api/v1/posts/read/42", INVALID, 404,
                "{\"code\":404,\"message\":\"게시글을 찾을 수 없습니다.\",\"data\":null}",
            ) { every { RelaxedBeans.mock<ReadPost>().read(any()) } throws PostNotFoundException() },
            Row(
                "게시글 조회 런타임 실패", "/api/v1/posts/read/42", INVALID, 500,
                "{\"code\":500,\"message\":\"서버 에러!\",\"data\":null}",
            ) { every { RelaxedBeans.mock<ReadPost>().read(any()) } throws IllegalStateException("synthetic-private-post") },
            Row(
                "관리자 집계 런타임 실패", "/api/v1/admin/users/stats/total-users", admin, 500,
                "{\"code\":500,\"message\":\"서버 에러!\",\"data\":null}",
            ) { every { RelaxedBeans.mock<TotalUserCountQuery>().count() } throws IllegalStateException("synthetic-admin-count") },
            Row(
                "차단 상태 저장소 실패", "/api/v1/notes/blocks/status", user, 503,
                "{\"message\":\"$STORAGE\"}", "no-store", mapOf("memberIds" to "8"),
            ) {
                every { RelaxedBeans.mock<ReadBlockedAuthors>().read(any(), any()) } throws
                    TransactionSystemException("synthetic-status-commit")
            },
            Row(
                "회원 없음", "/api/v1/users/me", user, 400,
                "{\"code\":400,\"message\":\"존재하지 않는 회원입니다.\",\"data\":null}",
            ) { every { RelaxedBeans.mock<UserProfileQuery>().require(any()) } throws UserNotFoundException() },
        )
    }
}
