package com.pawbridge.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.pawbridge.backend.boot.configuration.ReadSecurityConfiguration;
import com.pawbridge.backend.community.internal.note.application.SendPrivateNote;
import com.pawbridge.backend.community.internal.note.application.SendPrivateNote.SendNote;
import com.pawbridge.backend.community.internal.note.web.NoteSendController;
import com.pawbridge.backend.community.internal.note.web.NoteSendExceptionHandler;
import io.jsonwebtoken.Jwts;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.MockMvc;

/** Real MVC/JWT boundary only; the mock executes no SQL, commit or SSE. */
@WebMvcTest(controllers = NoteSendController.class,
        excludeAutoConfiguration = org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration.class)
@ContextConfiguration(classes = {NoteSendController.class, NoteSendExceptionHandler.class,
        ReadSecurityConfiguration.class, F3TestInputs.SecurityInputs.class})
class NoteSendHttpTest {
    private static final UUID NOTE = UUID.fromString("f2900000-0000-0000-0000-000000000001");
    private static final UUID REQUEST = UUID.fromString("f2900000-0000-0000-0000-000000000002");
    private static final String SECURITY_CACHE = "no-cache, no-store, max-age=0, must-revalidate";
    private static final String BODY = "{\"recipientId\":8,\"body\":\" raw body \",\"requestId\":\"" + REQUEST + "\",\"replyTo\":null,\"contextType\":null,\"contextId\":null}";
    @Autowired MockMvc mvc;
    @MockitoBean SendPrivateNote sending;

    @ParameterizedTest @ValueSource(strings = {"USER", "EXPIRYLESS_ADMIN"})
    void givenSignedRestCallerAndSpoofedHeaders__whenSending__thenBindVerifiedIdentityAndPreserveSixInputs(String kind) throws Exception {
        when(sending.send(eq(7L), any())).thenReturn(new SendPrivateNote.Receipt(NOTE));
        String token = kind.equals("USER") ? F3TestInputs.token(7, "ROLE_USER")
                : Jwts.builder().claim("userId", 7L).claim("role", "ROLE_ADMIN").signWith(F3TestInputs.KEY).compact();
        mvc.perform(post("/api/v1/notes").header("Authorization", "Bearer " + token).header("X-User-Id", "999")
                        .header("X-User-Role", "ROLE_ADMIN").contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().doesNotExist("Set-Cookie"))
                .andExpect(content().json("{\"code\":200,\"message\":\"요청이 성공적으로 처리되었습니다.\",\"data\":{\"noteId\":\"" + NOTE + "\"}}", JsonCompareMode.STRICT));
        verify(sending).send(7L, new SendNote(8L, " raw body ", REQUEST, null, null, null));
        verifyNoMoreInteractions(sending); cleared();
    }

    @ParameterizedTest @ValueSource(strings = {"MISSING", "INVALID"})
    void givenNoValidTokenAndMalformedBody__whenSending__thenAuthenticateBeforeBindingWithoutCallingTheUseCase(String kind) throws Exception {
        var request = post("/api/v1/notes").contentType(MediaType.APPLICATION_JSON).content("{malformed").header("X-User-Id", "7");
        if (kind.equals("INVALID")) request.header("Authorization", "Bearer invalid");
        mvc.perform(request).andExpect(status().isUnauthorized())
                .andExpect(header().string("Cache-Control", SECURITY_CACHE)).andExpect(header().doesNotExist("Set-Cookie"))
                .andExpect(jsonPath("$.code").value(401));
        verifyNoInteractions(sending); cleared();
    }

    @Test
    void givenVerifiedCallerAndMalformedBody__whenSending__thenUseScoped400WithoutInvokingTheUseCase() throws Exception {
        mvc.perform(post("/api/v1/notes").header("Authorization", "Bearer " + F3TestInputs.token(7, "ROLE_USER"))
                        .contentType(MediaType.APPLICATION_JSON).content("{malformed"))
                .andExpect(status().isBadRequest()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().doesNotExist("Set-Cookie"))
                .andExpect(content().json("{\"message\":\"요청 형식과 로그인 정보를 확인해 주세요.\"}", JsonCompareMode.STRICT));
        verifyNoInteractions(sending); cleared();
    }

    @ParameterizedTest @ValueSource(strings = {"/api/notes", "/api/v1/notes/unexposed/send"})
    void givenAdjacentUnexposedPost__whenUsingAValidToken__thenKeepDenyAllWithoutCallingTheUseCase(String path) throws Exception {
        mvc.perform(post(path).header("Authorization", "Bearer " + F3TestInputs.token(7, "ROLE_USER"))
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isForbidden()).andExpect(header().doesNotExist("Set-Cookie"));
        verifyNoInteractions(sending); cleared();
    }
    private static void cleared() { assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull(); }
}
