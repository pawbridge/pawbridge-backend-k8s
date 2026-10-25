package com.pawbridge.backend.user.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.pawbridge.backend.user.internal.adminupdate.application.AdminUserUpdate;
import com.pawbridge.backend.user.internal.adminupdate.web.AdminUserUpdateController;
import com.pawbridge.backend.user.internal.adminupdate.web.AdminUserUpdateExceptionHandler;
import com.pawbridge.backend.user.internal.deletion.application.UserDeletion;
import com.pawbridge.backend.user.internal.deletion.web.AdminUserDeletionController;
import com.pawbridge.backend.user.internal.deletion.web.AdminUserDeletionExceptionHandler;
import com.pawbridge.backend.user.internal.favorite.application.FavoriteStatusQuery;
import com.pawbridge.backend.user.internal.favorite.web.FavoriteStatusController;
import com.pawbridge.backend.user.internal.favorite.web.FavoriteStatusExceptionHandler;
import com.pawbridge.backend.user.internal.profile.application.UserProfileQuery;
import com.pawbridge.backend.user.internal.profile.web.AdminUserDetailController;
import com.pawbridge.backend.user.internal.profile.web.UserProfileExceptionHandler;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.MissingPathVariableException;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/**
 * Locks in the Java behaviour of user path variables after the Java to Kotlin conversion.
 *
 * <p>A Kotlin {@code @PathVariable id: Long?} makes Spring treat the variable as optional, so a blank
 * segment ({@code /%20}) reached the service as null; a Kotlin {@code Long} is a primitive, so a blank
 * segment became a type mismatch instead. The Java original declared a required boxed {@code Long}, which
 * fails with {@link MissingPathVariableException} before the handler runs. Every path variable of the
 * controllers below must keep that behaviour.
 *
 * <p>Standalone MockMvc with the module exception handlers and mocked services only. It does not cover the
 * servlet container, the security filter chain or error dispatch.
 */
class UserPathVariableRegressionTest {

    /** Expanded into the URI template, so it reaches Spring as the blank segment {@code %20}. */
    private static final String BLANK_SEGMENT = " ";

    private static final long ID = 10L;

    private static final long PRINCIPAL_ID = 7L;

    private static final String UPDATE_BODY = "{\"nickname\":\"abcd\"}";

    private final AdminUserUpdate update = mock(AdminUserUpdate.class);
    private final UserDeletion deletion = mock(UserDeletion.class);
    private final FavoriteStatusQuery favorites = mock(FavoriteStatusQuery.class);
    private final UserProfileQuery profiles = mock(UserProfileQuery.class);

    private MockMvc updateMvc;
    private MockMvc deletionMvc;
    private MockMvc favoriteMvc;
    private MockMvc detailMvc;

    @BeforeEach
    void setUp() {
        updateMvc = MockMvcBuilders
                .standaloneSetup(new AdminUserUpdateController(update))
                .setControllerAdvice(new AdminUserUpdateExceptionHandler())
                .build();
        deletionMvc = MockMvcBuilders
                .standaloneSetup(new AdminUserDeletionController(deletion))
                .setControllerAdvice(new AdminUserDeletionExceptionHandler())
                .build();
        favoriteMvc = MockMvcBuilders
                .standaloneSetup(new FavoriteStatusController(favorites))
                .setCustomArgumentResolvers(new FixedPrincipalResolver(PRINCIPAL_ID))
                .setControllerAdvice(new FavoriteStatusExceptionHandler())
                .build();
        detailMvc = MockMvcBuilders
                .standaloneSetup(new AdminUserDetailController(profiles))
                .setControllerAdvice(new UserProfileExceptionHandler())
                .build();
    }

    // AdminUserUpdateController

    @Test
    void givenBlankUserId__whenAdminUpdatingUser__thenFailAsMissingPathVariableWithoutCallingService() throws Exception {
        MvcResult result = updateMvc.perform(put("/api/v1/admin/users/{userId}", BLANK_SEGMENT)
                .contentType(MediaType.APPLICATION_JSON)
                .content(UPDATE_BODY)).andReturn();

        assertJavaOriginalMissingPathVariable(result);
        verifyNoInteractions(update);
    }

    @Test
    void givenNumericUserId__whenAdminUpdatingUser__thenPassTheIdToService() throws Exception {
        updateMvc.perform(put("/api/v1/admin/users/{userId}", ID)
                .contentType(MediaType.APPLICATION_JSON)
                .content(UPDATE_BODY));

        verify(update).update(eq(ID), any(AdminUserUpdate.Changes.class));
    }

    // AdminUserDeletionController

    @Test
    void givenBlankUserId__whenAdminDeletingUser__thenFailAsMissingPathVariableWithoutCallingService() throws Exception {
        MvcResult result = deletionMvc.perform(delete("/api/v1/admin/users/{userId}", BLANK_SEGMENT)).andReturn();

        assertJavaOriginalMissingPathVariable(result);
        verifyNoInteractions(deletion);
    }

    @Test
    void givenNumericUserId__whenAdminDeletingUser__thenPassTheIdToService() throws Exception {
        deletionMvc.perform(delete("/api/v1/admin/users/{userId}", ID));

        verify(deletion).delete(ID);
    }

    // FavoriteStatusController

    @Test
    void givenBlankAnimalId__whenCheckingFavorite__thenFailAsMissingPathVariableWithoutCallingService() throws Exception {
        MvcResult result = favoriteMvc.perform(get("/api/v1/favorites/{animalId}/check", BLANK_SEGMENT)).andReturn();

        assertJavaOriginalMissingPathVariable(result);
        verifyNoInteractions(favorites);
    }

    @Test
    void givenNumericAnimalId__whenCheckingFavorite__thenPassThePrincipalAndTheIdToService() throws Exception {
        favoriteMvc.perform(get("/api/v1/favorites/{animalId}/check", ID));

        verify(favorites).isFavorite(PRINCIPAL_ID, ID);
    }

    // AdminUserDetailController

    @Test
    void givenBlankUserId__whenAdminGettingUserDetail__thenFailAsMissingPathVariableWithoutCallingService() throws Exception {
        MvcResult result = detailMvc.perform(get("/api/v1/admin/users/{userId}", BLANK_SEGMENT)).andReturn();

        assertJavaOriginalMissingPathVariable(result);
        verifyNoInteractions(profiles);
    }

    @Test
    void givenNumericUserId__whenAdminGettingUserDetail__thenPassTheIdToService() throws Exception {
        detailMvc.perform(get("/api/v1/admin/users/{userId}", ID));

        verify(profiles).require(ID);
    }

    // helpers

    /**
     * Java original: no module handler matches, so Spring's default resolver answers 400 with an empty body
     * and MissingPathVariableException is the resolved exception.
     */
    private static void assertJavaOriginalMissingPathVariable(MvcResult result) throws Exception {
        assertEquals(400, result.getResponse().getStatus());
        assertEquals("", result.getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertInstanceOf(MissingPathVariableException.class, result.getResolvedException());
    }

    /** Stands in for the Spring Security principal resolver that standalone MockMvc does not register. */
    private static final class FixedPrincipalResolver implements HandlerMethodArgumentResolver {
        private final Long principalId;

        private FixedPrincipalResolver(Long principalId) {
            this.principalId = principalId;
        }

        @Override
        public boolean supportsParameter(MethodParameter parameter) {
            return parameter.hasParameterAnnotation(AuthenticationPrincipal.class);
        }

        @Override
        public Object resolveArgument(
                MethodParameter parameter,
                ModelAndViewContainer mavContainer,
                NativeWebRequest webRequest,
                WebDataBinderFactory binderFactory) {
            return principalId;
        }
    }
}
