package com.pawbridge.backend;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.pawbridge.backend.boot.configuration.ReadSecurityConfiguration;
import com.pawbridge.backend.community.internal.post.application.*;
import com.pawbridge.backend.community.internal.post.web.*;
import com.pawbridge.backend.user.api.UserNicknameQuery;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@WebMvcTest(controllers={AdminPostUpdateController.class,AdminPostDeleteController.class},
        excludeAutoConfiguration=org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration.class)
@ContextConfiguration(classes={AdminPostUpdateController.class,AdminPostDeleteController.class,
        PostUpdateExceptionHandler.class,PostDeleteExceptionHandler.class,ReadSecurityConfiguration.class,
        F3TestInputs.SecurityInputs.class,AdminPostMutationHttpTest.UseCases.class})
class AdminPostMutationHttpTest {
    private static final String ROOT="/api/v1/admin/posts";
    private static final String PRIVATE="synthetic-private-upload-or-db-value";
    @Autowired MockMvc mvc;
    @MockitoBean PostReader reader;
    @MockitoBean PostMediaRemover remover;
    @MockitoBean PostMediaUploader uploader;
    @MockitoBean UserNicknameQuery users;
    @MockitoBean PostModification writer;
    @MockitoBean PostDeletion deletion;
    private final LocalDateTime time=LocalDateTime.of(2026,10,20,12,0);
    private PostSnapshot existing;

    @TestConfiguration(proxyBeanMethods=false)
    static class UseCases {
        @Bean UpdatePost update(PostReader reader,PostMediaRemover remover,PostMediaUploader uploader,
                                UserNicknameQuery users,PostModification writer) {
            return new UpdatePost(reader,remover,uploader,users,writer);
        }
        @Bean DeletePost delete(PostReader reader,PostMediaRemover remover,PostDeletion deletion) {
            return new DeletePost(reader,remover,deletion);
        }
    }

    @BeforeEach void givenAnotherAuthorsExistingPost() {
        existing=new PostSnapshot(42,7,"title","content","MISSING",List.of("https://fixture.invalid/old"),time,time);
        when(reader.findById(42)).thenReturn(Optional.of(existing));
        when(users.findNickname(7)).thenReturn(Optional.of("작성자"));
        when(writer.update(eq(42L),eq(7L),nullable(String.class),nullable(String.class),nullable(List.class)))
                .thenAnswer(call->new PostSnapshot(42,7,call.getArgument(2)==null?existing.title():call.getArgument(2),
                        call.getArgument(3)==null?existing.content():call.getArgument(3),existing.boardType(),
                        call.getArgument(4)==null?existing.imageUrls():call.getArgument(4),time,time));
    }

    @Test void given__verified_admin_and_foreign_author__when__partial_put__then__keep_original_author_and_nine_fields_while_ignoring_forged_identity() throws Exception {
        mvc.perform(multipart(HttpMethod.PUT,ROOT+"/42").param("title","")
                        .param("authorId","99").header("X-User-Id","99").header("X-User-Role","ROLE_USER")
                        .header("Authorization",admin()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.message").value("게시글이 수정되었습니다."))
                .andExpect(jsonPath("$.data.length()").value(9)).andExpect(jsonPath("$.data.authorId").value(7))
                .andExpect(jsonPath("$.data.authorNickname").value("작성자")).andExpect(jsonPath("$.data.title").value(""))
                .andExpect(jsonPath("$.data.content").value("content")).andExpect(jsonPath("$.data.boardType").value("MISSING"))
                .andExpect(header().doesNotExist("Set-Cookie"));
        verify(writer).update(42,7,"",null,null);verify(users).findNickname(7);
        verifyNoInteractions(remover,uploader,deletion);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test void given__one_empty_file__when__admin_puts__then__replace_media_with_empty_list_using_existing_order() throws Exception {
        when(uploader.upload(anyList())).thenReturn(List.of());
        mvc.perform(multipart(HttpMethod.PUT,ROOT+"/42")
                        .file(new MockMultipartFile("files","empty.png","image/png",new byte[0])).header("Authorization",admin()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.imageUrls").isEmpty());
        var order=inOrder(reader,remover,uploader,users,writer);
        order.verify(reader).findById(42);order.verify(remover).remove(existing.imageUrls());
        order.verify(uploader).upload(argThat(files->files.size()==1&&files.getFirst().size()==0));
        order.verify(users).findNickname(7);order.verify(writer).update(42,7,null,null,List.of());
    }

    @Test void given__upload_failure_after_old_media_attempt__when__admin_puts__then__return_safe_500_without_db_or_compensation() throws Exception {
        when(uploader.upload(anyList())).thenThrow(new IllegalStateException(PRIVATE));
        mvc.perform(multipart(HttpMethod.PUT,ROOT+"/42")
                        .file(new MockMultipartFile("files","a.png","image/png",new byte[]{1})).header("Authorization",admin()))
                .andExpect(status().isInternalServerError()).andExpect(jsonPath("$.message").value("서버 에러!"))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString(PRIVATE))));
        verify(remover).remove(existing.imageUrls());verifyNoMoreInteractions(remover);
        verifyNoInteractions(users,writer,deletion);
    }

    @Test void given__foreign_post_then_already_deleted__when__admin_deletes_twice__then__first_returns_empty_data_and_second_404_without_repeated_effects() throws Exception {
        mvc.perform(delete(ROOT+"/42").header("Authorization",admin())).andExpect(status().isOk())
                .andExpect(content().json("{\"code\":200,\"message\":\"게시글이 삭제되었습니다.\",\"data\":null}"))
                .andExpect(header().doesNotExist("Set-Cookie"));
        when(reader.findById(42)).thenReturn(Optional.empty());
        mvc.perform(delete(ROOT+"/42").header("Authorization",admin())).andExpect(status().isNotFound());
        var order=inOrder(remover,deletion);order.verify(remover).remove(existing.imageUrls());
        order.verify(deletion).delete(42,7);order.verifyNoMoreInteractions();
        verifyNoInteractions(uploader,users,writer);
    }

    @ParameterizedTest
    @CsvSource({"PUT,missing,401","DELETE,missing,401","PUT,ROLE_USER,403","DELETE,ROLE_USER,403","PUT,ROLE_SHELTER,403","DELETE,ROLE_SHELTER,403"})
    void given__missing_or_non_admin_identity_and_forged_admin_header__when__requesting_bad_id__then__deny_before_binding_or_effects(String method,String role,int status) throws Exception {
        var request=mutation(method,"bad-id").header("X-User-Role","ROLE_ADMIN").header("X-User-Id","99");
        if(!role.equals("missing"))request.header("Authorization","Bearer "+F3TestInputs.token(99,role));
        mvc.perform(request).andExpect(status().is(status));
        verifyNoInteractions(reader,remover,uploader,users,writer,deletion);
    }

    @ParameterizedTest @CsvSource({"PUT,missing,404","DELETE,missing,404","PUT,db,500","DELETE,db,500"})
    void given__missing_or_late_writer_failure__when__admin_mutates__then__reuse_scoped_response_and_safe_metadata_only_log(String method,String mode,int expected) throws Exception {
        if(mode.equals("missing"))when(reader.findById(42)).thenReturn(Optional.empty());
        else if(method.equals("PUT"))when(writer.update(42,7,null,null,null)).thenThrow(new IllegalStateException(PRIVATE));
        else doThrow(new IllegalStateException(PRIVATE)).when(deletion).delete(42,7);
        Logger logger=(Logger)LoggerFactory.getLogger(method.equals("PUT")?PostUpdateExceptionHandler.class:PostDeleteExceptionHandler.class);
        ListAppender<ILoggingEvent> events=new ListAppender<>();events.start();logger.addAppender(events);
        try {
            mvc.perform(mutation(method,"42").header("Authorization",admin())).andExpect(status().is(expected))
                    .andExpect(jsonPath("$.message").value(expected==404?"게시글을 찾을 수 없습니다.":"서버 에러!"))
                    .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString(PRIVATE))));
            assertThat(events.list).allSatisfy(event->{assertThat(event.getFormattedMessage()).doesNotContain(PRIVATE);assertThat(event.getThrowableProxy()).isNull();});
            if(expected==500)assertThat(events.list).anySatisfy(event->assertThat(event.getFormattedMessage()).contains("java.lang.IllegalStateException"));
            else verifyNoInteractions(remover,uploader,users,writer,deletion);
        } finally {logger.detachAppender(events);events.stop();}
    }

    @ParameterizedTest @ValueSource(strings={"PUT","DELETE"})
    void given__admin_and_invalid_numeric_path__when__binding__then__preserve_original_500_before_business(String method) throws Exception {
        mvc.perform(mutation(method,"not-a-long").header("Authorization",admin()))
                .andExpect(status().isInternalServerError()).andExpect(jsonPath("$.message").value("서버 에러!"));
        verifyNoInteractions(reader,remover,uploader,users,writer,deletion);
    }

    @ParameterizedTest @ValueSource(strings={"PUT","DELETE"})
    void given__admin_on_deeper_unported_path__when__requesting__then__retain_default_deny(String method) throws Exception {
        mvc.perform(mutation(method,"42/extra").header("Authorization",admin())).andExpect(status().isForbidden());
        verifyNoInteractions(reader,remover,uploader,users,writer,deletion);
    }

    private static MockHttpServletRequestBuilder mutation(String method,String id) {
        return method.equals("PUT")?multipart(HttpMethod.PUT,ROOT+"/"+id):delete(ROOT+"/"+id);
    }
    private static String admin() {return "Bearer "+F3TestInputs.token(99,"ROLE_ADMIN");}
}
