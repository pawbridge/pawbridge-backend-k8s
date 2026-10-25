package com.pawbridge.backend.user.internal

import com.pawbridge.backend.user.anyNonNull
import com.pawbridge.backend.user.internal.adminupdate.application.AdminUserUpdate
import com.pawbridge.backend.user.internal.adminupdate.web.AdminUserUpdateController
import com.pawbridge.backend.user.internal.adminupdate.web.AdminUserUpdateExceptionHandler
import com.pawbridge.backend.user.internal.deletion.application.UserDeletion
import com.pawbridge.backend.user.internal.deletion.web.AdminUserDeletionController
import com.pawbridge.backend.user.internal.deletion.web.AdminUserDeletionExceptionHandler
import com.pawbridge.backend.user.internal.favorite.application.FavoriteStatusQuery
import com.pawbridge.backend.user.internal.favorite.web.FavoriteStatusController
import com.pawbridge.backend.user.internal.favorite.web.FavoriteStatusExceptionHandler
import com.pawbridge.backend.user.internal.profile.application.UserProfileQuery
import com.pawbridge.backend.user.internal.profile.web.AdminUserDetailController
import com.pawbridge.backend.user.internal.profile.web.UserProfileExceptionHandler
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
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
    private val update: AdminUserUpdate = mock(AdminUserUpdate::class.java)
    private val deletion: UserDeletion = mock(UserDeletion::class.java)
    private val favorites: FavoriteStatusQuery = mock(FavoriteStatusQuery::class.java)
    private val profiles: UserProfileQuery = mock(UserProfileQuery::class.java)

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
    fun givenBlankUserId__whenAdminUpdatingUser__thenFailAsMissingPathVariableWithoutCallingService() {
        val result = updateMvc.perform(
            put("/api/v1/admin/users/{userId}", BLANK_SEGMENT)
                .contentType(MediaType.APPLICATION_JSON)
                .content(UPDATE_BODY),
        ).andReturn()

        assertJavaOriginalMissingPathVariable(result)
        verifyNoInteractions(update)
    }

    @Test
    fun givenNumericUserId__whenAdminUpdatingUser__thenPassTheIdToService() {
        updateMvc.perform(
            put("/api/v1/admin/users/{userId}", ID)
                .contentType(MediaType.APPLICATION_JSON)
                .content(UPDATE_BODY),
        )

        verify(update).update(eq(ID), anyNonNull<AdminUserUpdate.Changes>())
    }

    // AdminUserDeletionController

    @Test
    fun givenBlankUserId__whenAdminDeletingUser__thenFailAsMissingPathVariableWithoutCallingService() {
        val result = deletionMvc.perform(delete("/api/v1/admin/users/{userId}", BLANK_SEGMENT)).andReturn()

        assertJavaOriginalMissingPathVariable(result)
        verifyNoInteractions(deletion)
    }

    @Test
    fun givenNumericUserId__whenAdminDeletingUser__thenPassTheIdToService() {
        deletionMvc.perform(delete("/api/v1/admin/users/{userId}", ID))

        verify(deletion).delete(ID)
    }

    // FavoriteStatusController

    @Test
    fun givenBlankAnimalId__whenCheckingFavorite__thenFailAsMissingPathVariableWithoutCallingService() {
        val result = favoriteMvc.perform(get("/api/v1/favorites/{animalId}/check", BLANK_SEGMENT)).andReturn()

        assertJavaOriginalMissingPathVariable(result)
        verifyNoInteractions(favorites)
    }

    @Test
    fun givenNumericAnimalId__whenCheckingFavorite__thenPassThePrincipalAndTheIdToService() {
        favoriteMvc.perform(get("/api/v1/favorites/{animalId}/check", ID))

        verify(favorites).isFavorite(PRINCIPAL_ID, ID)
    }

    // AdminUserDetailController

    @Test
    fun givenBlankUserId__whenAdminGettingUserDetail__thenFailAsMissingPathVariableWithoutCallingService() {
        val result = detailMvc.perform(get("/api/v1/admin/users/{userId}", BLANK_SEGMENT)).andReturn()

        assertJavaOriginalMissingPathVariable(result)
        verifyNoInteractions(profiles)
    }

    @Test
    fun givenNumericUserId__whenAdminGettingUserDetail__thenPassTheIdToService() {
        detailMvc.perform(get("/api/v1/admin/users/{userId}", ID))

        verify(profiles).require(ID)
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
