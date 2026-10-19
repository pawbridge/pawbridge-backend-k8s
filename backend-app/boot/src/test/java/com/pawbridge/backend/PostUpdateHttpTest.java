package com.pawbridge.backend;

import com.pawbridge.backend.boot.configuration.ReadSecurityConfiguration;
import com.pawbridge.backend.community.internal.post.application.*;
import com.pawbridge.backend.community.internal.post.web.PostUpdateController;
import com.pawbridge.backend.community.internal.post.web.PostUpdateExceptionHandler;
import com.pawbridge.backend.user.api.UserNicknameQuery;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpMethod;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers=PostUpdateController.class,
        excludeAutoConfiguration=org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration.class)
@ContextConfiguration(classes={PostUpdateController.class,PostUpdateExceptionHandler.class,ReadSecurityConfiguration.class,
        F3TestInputs.SecurityInputs.class,PostUpdateHttpTest.UseCase.class})
class PostUpdateHttpTest {
    @Autowired MockMvc mvc;
    @MockitoBean PostReader reader;
    @MockitoBean PostMediaRemover remover;
    @MockitoBean PostMediaUploader uploader;
    @MockitoBean UserNicknameQuery users;
    @MockitoBean PostModification writer;
    private final LocalDateTime time=LocalDateTime.of(2026,1,1,0,0);
    @TestConfiguration(proxyBeanMethods=false) static class UseCase {
        @Bean UpdatePost updatePost(PostReader reader,PostMediaRemover remover,PostMediaUploader uploader,UserNicknameQuery users,PostModification writer){
            return new UpdatePost(reader,remover,uploader,users,writer);
        }
    }
    @BeforeEach void givenOwnerAndReturningWriter() {
        when(reader.findById(42)).thenReturn(Optional.of(snapshot(7,"COMMUNICATION")));
        when(users.findNickname(anyLong())).thenReturn(Optional.of("작성자"));
        when(writer.update(anyLong(),anyLong(),nullable(String.class),nullable(String.class),nullable(List.class)))
                .thenAnswer(call->{
                    PostSnapshot old=reader.findById(call.getArgument(0)).orElseThrow();
                    String title=call.getArgument(2),content=call.getArgument(3);List<String> images=call.getArgument(4);
                    return new PostSnapshot(old.postId(),old.authorId(),title==null?old.title():title,content==null?old.content():content,
                            old.boardType(),images==null?old.imageUrls():images,time,time);
                });
    }
    @ParameterizedTest @ValueSource(strings={"ROLE_USER","ROLE_ADMIN","ROLE_SHELTER"})
    void given__verified_owner_with_forged_headers__when__multipart_put__then__return_nine_original_fields_and_verified_author(String role) throws Exception {
        mvc.perform(multipart(HttpMethod.PUT,"/api/v1/posts/42").param("title","수정 제목").param("content","")
                        .param("authorId","999").header("X-User-Id","999").header("X-User-Role","ROLE_ADMIN")
                        .header("Authorization","Bearer "+F3TestInputs.token(7,role)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.message").value("게시글이 수정되었습니다."))
                .andExpect(jsonPath("$.data.length()").value(9)).andExpect(jsonPath("$.data.authorId").value(7))
                .andExpect(jsonPath("$.data.authorNickname").value("작성자")).andExpect(jsonPath("$.data.title").value("수정 제목"))
                .andExpect(jsonPath("$.data.content").value("")).andExpect(header().doesNotExist("Set-Cookie"));
        verify(writer).update(42,7,"수정 제목","",null);verifyNoInteractions(remover,uploader);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }
    @ParameterizedTest @ValueSource(strings={"MISSING","PROTECTION","REPORT","ADOPTION","COMMUNICATION"})
    void given__existing_board__when__put_without_fields__then__do_not_apply_general_post_creation_restrictions(String board) throws Exception {
        when(reader.findById(42)).thenReturn(Optional.of(snapshot(7,board)));
        mvc.perform(multipart(HttpMethod.PUT,"/api/v1/posts/42").header("Authorization","Bearer "+F3TestInputs.token(7,"ROLE_USER")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.boardType").value(board)).andExpect(jsonPath("$.data.title").value("제목"));
        verify(writer).update(42,7,null,null,null);verifyNoInteractions(remover,uploader);
    }
    @Test void given__one_empty_multipart_file__when__put__then__request_media_replacement_with_an_empty_result() throws Exception {
        when(uploader.upload(anyList())).thenReturn(List.of());
        mvc.perform(multipart(HttpMethod.PUT,"/api/v1/posts/42").file(new MockMultipartFile("files","empty.png","image/png",new byte[0]))
                        .header("Authorization","Bearer "+F3TestInputs.token(7,"ROLE_USER")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.imageUrls").isEmpty());
        verify(remover).remove(snapshot(7,"COMMUNICATION").imageUrls());
        verify(uploader).upload(argThat(files->files.size()==1&&files.getFirst().size()==0));
        verify(writer).update(42,7,null,null,List.of());
    }
    @Test void given__long_title_without_legacy_web_validation__when__put__then__pass_input_to_writer_instead_of_adding_400() throws Exception {
        String title="x".repeat(201);
        mvc.perform(multipart(HttpMethod.PUT,"/api/v1/posts/42").param("title",title)
                        .header("Authorization","Bearer "+F3TestInputs.token(7,"ROLE_USER")))
                .andExpect(status().isOk());verify(writer).update(42,7,title,null,null);
    }
    @Test void given__foreign_administrator__when__put__then__403_before_media_nickname_or_writer() throws Exception {
        mvc.perform(multipart(HttpMethod.PUT,"/api/v1/posts/42").header("Authorization","Bearer "+F3TestInputs.token(8,"ROLE_ADMIN")))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.message").value("게시글에 접근할 권한이 없습니다."));
        verifyNoInteractions(remover,uploader,users,writer);
    }
    @Test void given__missing_post__when__put__then__404_before_media_nickname_or_writer() throws Exception {
        when(reader.findById(42)).thenReturn(Optional.empty());
        mvc.perform(multipart(HttpMethod.PUT,"/api/v1/posts/42").header("Authorization","Bearer "+F3TestInputs.token(7,"ROLE_USER")))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.message").value("게시글을 찾을 수 없습니다."));
        verifyNoInteractions(remover,uploader,users,writer);
    }
    @ParameterizedTest @ValueSource(strings={"missing","invalid","refresh","expired"})
    void given__unverified_token__when__put__then__401_before_all_business_ports(String token) throws Exception {
        var request=multipart(HttpMethod.PUT,"/api/v1/posts/42").header("X-User-Id","7");
        String value=switch(token){
            case "invalid"->"invalid";
            case "refresh"->io.jsonwebtoken.Jwts.builder().subject("7").expiration(new java.util.Date(System.currentTimeMillis()+60_000)).signWith(F3TestInputs.KEY).compact();
            case "expired"->io.jsonwebtoken.Jwts.builder().claim("userId",7).claim("role","ROLE_USER").expiration(new java.util.Date(System.currentTimeMillis()-60_000)).signWith(F3TestInputs.KEY).compact();
            default->null;
        };
        if(value!=null)request.header("Authorization","Bearer "+value);
        mvc.perform(request).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value(401));
        verifyNoInteractions(reader,remover,uploader,users,writer);
    }
    @ParameterizedTest @ValueSource(strings={"abc","9223372036854775808"})
    void given__malformed_post_id__when__put__then__preserve_legacy_500(String id) throws Exception {
        mvc.perform(multipart(HttpMethod.PUT,"/api/v1/posts/"+id).header("Authorization","Bearer "+F3TestInputs.token(7,"ROLE_USER")))
                .andExpect(status().isInternalServerError()).andExpect(jsonPath("$.message").value("서버 에러!"));
        verifyNoInteractions(reader,remover,uploader,users,writer);
    }
    @ParameterizedTest @ValueSource(strings={"PUT /api/v1/posts","PUT /api/v1/posts/42/extra","PUT /api/v1/posts/read/42",
            "PUT /api/v1/admin/posts/42","PATCH /api/v1/posts/42","POST /api/v1/posts/42","GET /api/v1/posts/42"})
    void given__unexposed_path_or_method__when__requesting__then__deny_before_business(String request) throws Exception {
        String[] parts=request.split(" ",2);
        mvc.perform(request(HttpMethod.valueOf(parts[0]),parts[1]).header("Authorization","Bearer "+F3TestInputs.token(7,"ROLE_USER")))
                .andExpect(status().isForbidden());verifyNoInteractions(reader,remover,uploader,users,writer);
    }
    @Test void given__writer_failure_after_authentication__when__put__then__safe_500_and_clear_principal() throws Exception {
        when(writer.update(42,7,null,null,null)).thenThrow(new IllegalStateException("synthetic-private-db-detail"));
        mvc.perform(multipart(HttpMethod.PUT,"/api/v1/posts/42").header("Authorization","Bearer "+F3TestInputs.token(7,"ROLE_USER")))
                .andExpect(status().isInternalServerError()).andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("synthetic-private-db-detail"))));
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }
    private PostSnapshot snapshot(long author,String board){return new PostSnapshot(42,author,"제목","본문",board,List.of("https://fixture.invalid/old"),time,time);}
}
