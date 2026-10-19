package com.pawbridge.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.pawbridge.backend.boot.configuration.ReadSecurityConfiguration;
import com.pawbridge.backend.community.internal.note.application.NoteFavoriteMutation;
import com.pawbridge.backend.community.internal.note.application.SetPrivateNoteFavorite;
import com.pawbridge.backend.community.internal.note.web.NoteFavoriteController;
import com.pawbridge.backend.community.internal.note.web.NoteFavoriteExceptionHandler;
import com.pawbridge.backend.user.api.ContactMember;
import com.pawbridge.backend.user.api.ContactMemberQuery;
import com.pawbridge.backend.user.api.ContactMemberUnavailableException;
import com.pawbridge.backend.user.internal.statistics.application.TotalUserCountQuery;
import com.pawbridge.backend.user.internal.statistics.web.TotalUserCountController;
import com.pawbridge.backend.user.internal.statistics.web.TotalUserCountExceptionHandler;
import io.jsonwebtoken.Jwts;
import java.time.Clock;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.TransactionSystemException;

/** Real MVC/JWT and application callbacks; mocked mutation is not SQL, transaction or SSE evidence. */
@WebMvcTest(controllers = NoteFavoriteController.class,
        excludeAutoConfiguration = org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration.class)
@ContextConfiguration(classes = {NoteFavoriteController.class, NoteFavoriteExceptionHandler.class, SetPrivateNoteFavorite.class,
        TotalUserCountController.class, TotalUserCountExceptionHandler.class,
        ReadSecurityConfiguration.class, F3TestInputs.SecurityInputs.class})
class NoteFavoriteHttpTest {
    private static final UUID NOTE = UUID.fromString("f2700000-0000-0000-0000-000000000001");
    private static final String PATH = "/api/v1/notes/" + NOTE + "/favorite";
    private static final String ADMIN_COUNT = "/api/v1/admin/users/stats/total-users";
    private static final String SECURITY_CACHE = "no-cache, no-store, max-age=0, must-revalidate";

    @Autowired MockMvc mvc;
    @MockitoBean ContactMemberQuery members;
    @MockitoBean NoteFavoriteMutation mutation;
    @MockitoBean(name = "readClock") Clock clock;
    @MockitoBean TotalUserCountQuery counts;

    @ParameterizedTest @ValueSource(booleans = {true, false})
    void givenExplicitBooleanAndSignedCallerIncludingExpirylessAdminRestToken__whenSettingFavoriteWithSpoofedHeaders__thenKeepCallerValueAndEmptyNoStore200(boolean favorite) throws Exception {
        when(members.get(7)).thenReturn(active());
        doAnswer(call -> { call.getArgument(3, Runnable.class).run(); return null; })
                .when(mutation).set(eq(7L), eq(NOTE), eq(favorite), any(Runnable.class), any());
        // General REST accepts a verified expiryless token; only exact stream requires expiry.
        String token = favorite ? F3TestInputs.token(7, "ROLE_USER")
                : Jwts.builder().claim("userId", 7L).claim("role", "ROLE_ADMIN").signWith(F3TestInputs.KEY).compact();

        mvc.perform(put(PATH).header("Authorization", "Bearer " + token).header("X-User-Id", "999")
                        .header("X-User-Role", "ROLE_ADMIN").header("X-Auth-Expires-At", Long.MAX_VALUE)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"favorite\":" + favorite + "}"))
                .andExpect(status().isOk()).andExpect(content().string(""))
                .andExpect(header().string("Cache-Control", "no-store")).andExpect(header().doesNotExist("Set-Cookie"));

        verify(mutation).set(eq(7L), eq(NOTE), eq(favorite), any(Runnable.class), any());
        verify(members).get(7); verifyNoMoreInteractions(mutation, members); verifyNoInteractions(clock, counts); cleared();
    }

    @ParameterizedTest @ValueSource(strings = {"MISSING", "INVALID"})
    void givenMissingOrBadTokenAndMalformedUuidAndJson__whenSettingFavorite__thenAuthenticateBeforeEitherBinderWithoutDependencies(String kind) throws Exception {
        var request = put("/api/v1/notes/not-a-uuid/favorite").contentType(MediaType.APPLICATION_JSON).content("{broken");
        if (kind.equals("INVALID")) request.header("Authorization", "Bearer invalid");
        String message = kind.equals("MISSING") ? "인증 토큰이 필요합니다." : "유효하지 않거나 만료된 토큰입니다.";
        mvc.perform(request).andExpect(status().isUnauthorized()).andExpect(header().doesNotExist("Set-Cookie"))
                .andExpect(content().json("{\"code\":401,\"message\":\"" + message + "\",\"data\":null}", JsonCompareMode.STRICT));
        noDependencies();
    }

    @Test
    void givenVerifiedCallerAndMalformedUuid__whenSettingCanonicalBoolean__thenReturnScoped400WithoutCurrentOrMutation() throws Exception {
        mvc.perform(put("/api/v1/notes/not-a-uuid/favorite")
                        .header("Authorization", "Bearer " + F3TestInputs.token(7, "ROLE_USER"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"favorite\":true}"))
                .andExpect(status().isBadRequest()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().doesNotExist("Set-Cookie"))
                .andExpect(content().json("{\"message\":\"요청 형식과 로그인 정보를 확인해 주세요.\"}", JsonCompareMode.STRICT));
        noDependencies();
    }

    @ParameterizedTest @MethodSource("failures")
    void givenCurrentOrMutationFailure__whenSettingFavorite__thenKeepInactiveNotFoundMemberStorageAndWriterRuntimePolicies(String kind, int status, String message) throws Exception {
        RuntimeException failure = switch (kind) {
            case "inactive" -> null;
            case "missing" -> new NoteFavoriteMutation.MissingOwnedNote();
            case "member" -> new ContactMemberUnavailableException(new IllegalStateException("private-member"));
            case "DAO" -> new DataAccessResourceFailureException("private-SQL-body");
            default -> new TransactionSystemException("private-commit-detail");
        };
        if (kind.equals("member")) when(members.get(7)).thenThrow(failure);
        else when(members.get(7)).thenReturn(kind.equals("inactive") ? new ContactMember(7L, "", false, true) : active());
        doAnswer(call -> {
            call.getArgument(3, Runnable.class).run();
            if (failure != null) throw failure;
            return null;
        })
                .when(mutation).set(eq(7L), eq(NOTE), eq(true), any(Runnable.class), any());

        mvc.perform(put(PATH).header("Authorization", "Bearer " + F3TestInputs.token(7, "ROLE_USER"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"favorite\":true}"))
                .andExpect(status().is(status)).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().doesNotExist("Set-Cookie"))
                .andExpect(content().json("{\"message\":\"" + message + "\"}", JsonCompareMode.STRICT));

        verify(mutation).set(eq(7L), eq(NOTE), eq(true), any(Runnable.class), any());
        verify(members).get(7); verifyNoMoreInteractions(mutation, members); verifyNoInteractions(clock, counts); cleared();
    }

    static Stream<Arguments> failures() {
        return Stream.of(
                Arguments.of("inactive", 403, "이용할 수 없는 회원입니다."),
                Arguments.of("missing", 404, "쪽지를 찾을 수 없습니다."),
                Arguments.of("member", 503, "회원 정보를 확인할 수 없습니다. 잠시 후 다시 시도해 주세요."),
                Arguments.of("DAO", 503, "쪽지 저장소를 사용할 수 없습니다. 잠시 후 다시 시도해 주세요."),
                Arguments.of("TX", 503, "쪽지를 처리하지 못했습니다. 잠시 후 다시 시도해 주세요."));
    }

    @Test
    void givenUnexposedMethodOnTheFavoritePath__whenRequestingWithValidJwt__thenDenyBeforeFavoriteDependencies() throws Exception {
        mvc.perform(get(PATH).header("Authorization", "Bearer " + F3TestInputs.token(7, "ROLE_USER")))
                .andExpect(status().isForbidden()).andExpect(header().string("Cache-Control", SECURITY_CACHE))
                .andExpect(header().doesNotExist("Set-Cookie"));
        noDependencies();
    }

    @Test
    void givenAdminCountOutsideFavoriteAdvice__whenUserOrAdminRequestsWithSpoofedRole__thenKeepRole403AndCommon500() throws Exception {
        when(counts.count()).thenThrow(new IllegalStateException("private-count"));
        mvc.perform(get(ADMIN_COUNT).header("Authorization", "Bearer " + F3TestInputs.token(7, "ROLE_USER"))
                        .header("X-User-Role", "ROLE_ADMIN"))
                .andExpect(status().isForbidden()).andExpect(header().string("Cache-Control", SECURITY_CACHE))
                .andExpect(header().doesNotExist("Set-Cookie"))
                .andExpect(content().json("{\"code\":403,\"message\":\"관리자 권한이 필요합니다.\",\"data\":null}", JsonCompareMode.STRICT));
        mvc.perform(get(ADMIN_COUNT).header("Authorization", "Bearer " + F3TestInputs.token(7, "ROLE_ADMIN")))
                .andExpect(status().isInternalServerError()).andExpect(header().string("Cache-Control", SECURITY_CACHE))
                .andExpect(header().doesNotExist("Set-Cookie"))
                .andExpect(content().json("{\"code\":500,\"message\":\"서버 에러!\",\"data\":null}", JsonCompareMode.STRICT));
        verify(counts).count(); verifyNoMoreInteractions(counts); verifyNoInteractions(members, mutation, clock); cleared();
    }

    private static ContactMember active() { return new ContactMember(7L, " 原文 ", true, false); }
    private void noDependencies() { verifyNoInteractions(members, mutation, clock, counts); cleared(); }
    private static void cleared() { assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull(); }
}
