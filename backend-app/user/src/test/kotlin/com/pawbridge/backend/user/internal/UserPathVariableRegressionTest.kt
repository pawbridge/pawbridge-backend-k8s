package com.pawbridge.backend.user.internal

import com.pawbridge.backend.user.internal.adminupdate.application.AdminUserUpdate
import com.pawbridge.backend.user.internal.adminupdate.web.AdminUserUpdateController
import com.pawbridge.backend.user.internal.adminupdate.web.AdminUserUpdateExceptionHandler
import com.pawbridge.backend.user.internal.deletion.application.UserDeletion
import com.pawbridge.backend.user.internal.deletion.web.AdminUserDeletionController
import com.pawbridge.backend.user.internal.deletion.web.AdminUserDeletionExceptionHandler
import com.pawbridge.backend.user.internal.favorite.application.FavoriteStatusQuery
import com.pawbridge.backend.user.internal.favorite.web.FavoriteStatusController
import com.pawbridge.backend.user.internal.favorite.web.FavoriteStatusExceptionHandler
import com.pawbridge.backend.user.internal.profile.application.UserProfile
import com.pawbridge.backend.user.internal.profile.application.UserProfileQuery
import com.pawbridge.backend.user.internal.profile.web.AdminUserDetailController
import com.pawbridge.backend.user.internal.profile.web.UserProfileExceptionHandler
import io.mockk.confirmVerified
import io.mockk.every
import io.mockk.justRun
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.core.MethodParameter
import org.springframework.http.MediaType
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.MvcResult
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.bind.MissingPathVariableException
import org.springframework.web.bind.support.WebDataBinderFactory
import org.springframework.web.context.request.NativeWebRequest
import org.springframework.web.method.support.HandlerMethodArgumentResolver
import org.springframework.web.method.support.ModelAndViewContainer
import java.nio.charset.StandardCharsets

/**
 * Locks in the Java behaviour of user path variables after the Java to Kotlin conversion.
 *
 * A Kotlin `@PathVariable id: Long?` makes Spring treat the variable as optional, so a blank
 * segment (`/%20`) reached the service as null; a Kotlin `Long` is a primitive, so a blank
 * segment became a type mismatch instead. The Java original declared a required boxed `Long`, which
 * fails with [MissingPathVariableException] before the handler runs. Every path variable of the
 * controllers below must keep that behaviour.
 *
 * Standalone MockMvc with the module exception handlers and mocked services only. It does not cover the
 * servlet container, the security filter chain or error dispatch.
 */
class UserPathVariableRegressionTest {
    private val update: AdminUserUpdate = mockk<AdminUserUpdate>()
    private val deletion: UserDeletion = mockk<UserDeletion>()
    private val favorites: FavoriteStatusQuery = mockk<FavoriteStatusQuery>()
    private val profiles: UserProfileQuery = mockk<UserProfileQuery>()

    private lateinit var updateMvc: MockMvc
    private lateinit var deletionMvc: MockMvc
    private lateinit var favoriteMvc: MockMvc
    private lateinit var detailMvc: MockMvc

    @BeforeEach
    fun setUp() {
        updateMvc = MockMvcBuilders
            .standaloneSetup(AdminUserUpdateController(update))
            .setControllerAdvice(AdminUserUpdateExceptionHandler())
            .build()
        deletionMvc = MockMvcBuilders
            .standaloneSetup(AdminUserDeletionController(deletion))
            .setControllerAdvice(AdminUserDeletionExceptionHandler())
            .build()
        favoriteMvc = MockMvcBuilders
            .standaloneSetup(FavoriteStatusController(favorites))
            .setCustomArgumentResolvers(FixedPrincipalResolver(PRINCIPAL_ID))
            .setControllerAdvice(FavoriteStatusExceptionHandler())
            .build()
        detailMvc = MockMvcBuilders
            .standaloneSetup(AdminUserDetailController(profiles))
            .setControllerAdvice(UserProfileExceptionHandler())
            .build()
    }

    // AdminUserUpdateController

    @Test
    fun `사용자 아이디가 공백일 때__관리자가 사용자를 수정하면__서비스를 호출하지 않고 경로 변수 누락으로 실패한다`() {
        // given
        val request = put("/api/v1/admin/users/{userId}", BLANK_SEGMENT)
            .contentType(MediaType.APPLICATION_JSON)
            .content(UPDATE_BODY)

        // when
        val result = updateMvc.perform(request).andReturn()

        // then
        assertJavaOriginalMissingPathVariable(result)
        confirmVerified(update)
    }

    @Test
    fun `숫자 사용자 아이디일 때__관리자가 사용자를 수정하면__아이디를 서비스에 그대로 넘긴다`() {
        // given
        justRun { update.update(any(), any()) }

        // when
        updateMvc.perform(
            put("/api/v1/admin/users/{userId}", ID)
                .contentType(MediaType.APPLICATION_JSON)
                .content(UPDATE_BODY),
        )

        // then
        verify(exactly = 1) { update.update(ID, any()) }
    }

    // AdminUserDeletionController

    @Test
    fun `사용자 아이디가 공백일 때__관리자가 사용자를 삭제하면__서비스를 호출하지 않고 경로 변수 누락으로 실패한다`() {
        // given
        val request = delete("/api/v1/admin/users/{userId}", BLANK_SEGMENT)

        // when
        val result = deletionMvc.perform(request).andReturn()

        // then
        assertJavaOriginalMissingPathVariable(result)
        confirmVerified(deletion)
    }

    @Test
    fun `숫자 사용자 아이디일 때__관리자가 사용자를 삭제하면__아이디를 서비스에 그대로 넘긴다`() {
        // given
        justRun { deletion.delete(any()) }

        // when
        deletionMvc.perform(delete("/api/v1/admin/users/{userId}", ID))

        // then
        verify(exactly = 1) { deletion.delete(ID) }
    }

    // FavoriteStatusController

    @Test
    fun `동물 아이디가 공백일 때__즐겨찾기를 확인하면__서비스를 호출하지 않고 경로 변수 누락으로 실패한다`() {
        // given
        val request = get("/api/v1/favorites/{animalId}/check", BLANK_SEGMENT)

        // when
        val result = favoriteMvc.perform(request).andReturn()

        // then
        assertJavaOriginalMissingPathVariable(result)
        confirmVerified(favorites)
    }

    @Test
    fun `숫자 동물 아이디일 때__즐겨찾기를 확인하면__주체와 아이디를 서비스에 넘긴다`() {
        // given
        every { favorites.isFavorite(any(), any()) } returns false

        // when
        favoriteMvc.perform(get("/api/v1/favorites/{animalId}/check", ID))

        // then
        verify(exactly = 1) { favorites.isFavorite(PRINCIPAL_ID, ID) }
    }

    // AdminUserDetailController

    @Test
    fun `사용자 아이디가 공백일 때__관리자가 사용자 상세를 조회하면__서비스를 호출하지 않고 경로 변수 누락으로 실패한다`() {
        // given
        val request = get("/api/v1/admin/users/{userId}", BLANK_SEGMENT)

        // when
        val result = detailMvc.perform(request).andReturn()

        // then
        assertJavaOriginalMissingPathVariable(result)
        confirmVerified(profiles)
    }

    @Test
    fun `숫자 사용자 아이디일 때__관리자가 사용자 상세를 조회하면__아이디를 서비스에 그대로 넘긴다`() {
        // given
        every { profiles.require(any()) } returns UserProfile(ID, "member@fixture.invalid", "회원", "닉네임", "LOCAL", "ROLE_USER", null)

        // when
        detailMvc.perform(get("/api/v1/admin/users/{userId}", ID))

        // then
        verify(exactly = 1) { profiles.require(ID) }
    }

    // helpers

    /**
     * Java original: no module handler matches, so Spring's default resolver answers 400 with an empty body
     * and MissingPathVariableException is the resolved exception.
     */
    private fun assertJavaOriginalMissingPathVariable(result: MvcResult) {
        assertEquals(400, result.response.status)
        assertEquals("", result.response.getContentAsString(StandardCharsets.UTF_8))
        assertInstanceOf(MissingPathVariableException::class.java, result.resolvedException)
    }

    /** Stands in for the Spring Security principal resolver that standalone MockMvc does not register. */
    private class FixedPrincipalResolver(private val principalId: Long) : HandlerMethodArgumentResolver {
        override fun supportsParameter(parameter: MethodParameter): Boolean {
            return parameter.hasParameterAnnotation(AuthenticationPrincipal::class.java)
        }

        override fun resolveArgument(
            parameter: MethodParameter,
            mavContainer: ModelAndViewContainer?,
            webRequest: NativeWebRequest,
            binderFactory: WebDataBinderFactory?,
        ): Any? {
            return principalId
        }
    }

    companion object {
        /** Expanded into the URI template, so it reaches Spring as the blank segment `%20`. */
        private const val BLANK_SEGMENT = " "
        private const val ID = 10L
        private const val PRINCIPAL_ID = 7L
        private const val UPDATE_BODY = "{\"nickname\":\"abcd\"}"
    }
}
