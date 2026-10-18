package com.pawbridge.backend;

import com.pawbridge.backend.boot.configuration.ReadSecurityConfiguration;
import com.pawbridge.backend.community.internal.post.application.CreatePost;
import com.pawbridge.backend.community.internal.post.application.PostDetails;
import com.pawbridge.backend.community.internal.post.domain.PostBoardType;
import com.pawbridge.backend.community.internal.post.web.PostCreateController;
import com.pawbridge.backend.community.internal.post.web.PostCreateExceptionHandler;
import io.jsonwebtoken.Jwts;
import java.time.LocalDateTime;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers=PostCreateController.class, excludeAutoConfiguration=org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration.class)
@ContextConfiguration(classes={PostCreateController.class,PostCreateExceptionHandler.class,ReadSecurityConfiguration.class,F3TestInputs.SecurityInputs.class})
class PostCreateHttpTest {
    @Autowired MockMvc mvc;
    @MockitoBean CreatePost createPost;

    @ParameterizedTest
    @ValueSource(strings={"ROLE_USER","ROLE_ADMIN","ROLE_SHELTER"})
    void given__authenticated_writer_and_forged_headers__when__creating__then__use_verified_id_and_legacy_response(String role) throws Exception {
        when(createPost.create(eq(7L),eq("제목"),eq("내용"),eq(PostBoardType.COMMUNICATION),anyList()))
                .thenReturn(details(7));
        mvc.perform(multipart("/api/v1/posts").param("title","제목").param("content","내용")
                .param("boardType","COMMUNICATION").param("authorId","999")
                .header("X-User-Id","999").header("X-User-Role","ROLE_ADMIN")
                .header("Authorization","Bearer "+F3TestInputs.token(7,role)))
                .andExpect(status().isOk()).andExpect(header().doesNotExist("Set-Cookie"))
                .andExpect(jsonPath("$.code").value(200)).andExpect(jsonPath("$.message").value("게시글이 생성되었습니다."))
                .andExpect(jsonPath("$.data.length()").value(9)).andExpect(jsonPath("$.data.authorId").value(7));
        verify(createPost).create(7L,"제목","내용",PostBoardType.COMMUNICATION,List.of());
    }

    @Test
    void given__missing_or_invalid_token__when__posting__then__do_not_invoke_writing_use_case() throws Exception {
        mvc.perform(multipart("/api/v1/posts").header("X-User-Id","7"))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.message").value("인증 토큰이 필요합니다."));
        mvc.perform(multipart("/api/v1/posts").header("Authorization","Bearer invalid"))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.message").value("유효하지 않거나 만료된 토큰입니다."));
        verifyNoInteractions(createPost);
    }

    @ParameterizedTest
    @MethodSource("com.pawbridge.backend.PostTokenAuthenticatorTest#invalidIdentity")
    void given__signed_invalid_identity__when__posting__then__reject_before_writing(Map<String,Object> claims) throws Exception {
        String token=Jwts.builder().claims(claims).expiration(new Date(System.currentTimeMillis()+3_600_000))
                .signWith(F3TestInputs.KEY).compact();
        mvc.perform(multipart("/api/v1/posts").header("Authorization","Bearer "+token))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.message").value("토큰 파싱에 실패했습니다."));
        verifyNoInteractions(createPost);
    }

    @Test
    void given__two_writers_and_anonymous_next_request__when__posting__then__do_not_retain_the_previous_identity() throws Exception {
        when(createPost.create(anyLong(),anyString(),anyString(),any(),anyList()))
                .thenAnswer(call->details(call.getArgument(0)));
        for(long id:new long[]{7,8})
            mvc.perform(multipart("/api/v1/posts").param("title","제목").param("content","내용").param("boardType","COMMUNICATION")
                    .header("Authorization","Bearer "+F3TestInputs.token(id,"ROLE_USER")))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.data.authorId").value((int)id));
        mvc.perform(multipart("/api/v1/posts")).andExpect(status().isUnauthorized());
        verify(createPost,times(2)).create(anyLong(),anyString(),anyString(),any(),anyList());
    }

    @Test
    void given__business_failure_after_authentication__when__posting__then__do_not_relabel_it_as_401() throws Exception {
        when(createPost.create(anyLong(),anyString(),anyString(),any(),anyList())).thenThrow(new IllegalStateException("private-db-detail"));
        mvc.perform(multipart("/api/v1/posts").param("title","제목").param("content","내용").param("boardType","COMMUNICATION")
                .header("Authorization","Bearer "+F3TestInputs.token(7,"ROLE_USER")))
                .andExpect(status().isInternalServerError()).andExpect(jsonPath("$.message").value("서버 에러!"));
    }

    private static PostDetails details(long authorId) {
        return new PostDetails(42,authorId,"작성자","제목","내용","COMMUNICATION",List.of(),
                LocalDateTime.of(2026,10,18,12,0),LocalDateTime.of(2026,10,18,12,0));
    }
}
