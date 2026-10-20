package com.pawbridge.backend;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.pawbridge.backend.boot.configuration.ReadSecurityConfiguration;
import com.pawbridge.backend.community.internal.homevideo.application.HomeVideo;
import com.pawbridge.backend.community.internal.homevideo.application.HomeVideoManagement;
import com.pawbridge.backend.community.internal.homevideo.application.HomeVideoManagement.*;
import com.pawbridge.backend.community.internal.homevideo.web.HomeVideoManagementController;
import com.pawbridge.backend.community.internal.homevideo.web.HomeVideoManagementExceptionHandler;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;

@WebMvcTest(controllers=HomeVideoManagementController.class,properties="pawbridge.home-videos.management-enabled=true",excludeAutoConfiguration=org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration.class)
@ContextConfiguration(classes={HomeVideoManagementController.class,HomeVideoManagementExceptionHandler.class,ReadSecurityConfiguration.class,F3TestInputs.SecurityInputs.class})
class HomeVideoManagementHttpTest {
    static final String ROOT="/api/v1/admin/videos",ID="00000000-0000-0000-0000-000000000001",URL="https://youtu.be/AbCdEfGhI_1";
    @Autowired MockMvc mvc;
    @MockitoBean HomeVideoManagement videos;
    static Stream<Arguments> routes(){return Stream.of(
            Arguments.of("GET","","","board"),Arguments.of("POST","/preview","{\"url\":\""+URL+"\"}","preview"),
            Arguments.of("POST","","{\"url\":\""+URL+"\",\"published\":true,\"revision\":5}","create"),
            Arguments.of("PUT","/"+ID,"{\"url\":\""+URL+"\",\"published\":true,\"revision\":5}","edit"),
            Arguments.of("PUT","/"+ID+"/publication","{\"published\":false,\"revision\":5}","publish"),
            Arguments.of("PUT","/order","{\"ids\":[\""+ID+"\"],\"revision\":5}","order"),
            Arguments.of("POST","/"+ID+"/recheck","{\"published\":true,\"revision\":5}","recheck"));}
    @ParameterizedTest @MethodSource("routes")
    void given__a_verified_admin_and_spoofed_role__when__using_each_management_route__then__bind_exact_source_requests_and_existing_video_shape(String method,String suffix,String body,String operation)throws Exception {
        var id=UUID.fromString(ID);var instant=Instant.parse("2030-01-01T00:00:00.123456Z");
        var board=new Board(6,List.of(new HomeVideo(id,"AbCdEfGhI_1",true,0,instant,null,null,null,null,false,null)));
        var metadata=new Metadata("AbCdEfGhI_1","title","channel","https://i.ytimg.com/x.jpg",60,true);
        when(videos.board()).thenReturn(board);when(videos.preview(anyString())).thenReturn(metadata);when(videos.save(any(),any())).thenReturn(board);
        when(videos.publish(any(),any())).thenReturn(board);when(videos.reorder(any())).thenReturn(board);when(videos.recheck(any(),anyLong())).thenReturn(board);
        var result=mvc.perform(request(HttpMethod.valueOf(method),ROOT+suffix).contentType(MediaType.APPLICATION_JSON).content(body)
                        .header("Authorization",admin()).header("X-User-Role","ROLE_USER").header("X-User-Id","999"))
                .andExpect(status().isOk()).andExpect(header().doesNotExist("Set-Cookie"));
        if(operation.equals("preview"))result.andExpect(content().json("{\"code\":200,\"message\":\"요청이 성공적으로 처리되었습니다.\",\"data\":{\"videoId\":\"AbCdEfGhI_1\",\"title\":\"title\",\"channelTitle\":\"channel\",\"thumbnailUrl\":\"https://i.ytimg.com/x.jpg\",\"durationSeconds\":60,\"available\":true}}",JsonCompareMode.STRICT));
        else result.andExpect(content().json(BOARD,JsonCompareMode.STRICT));
        switch(operation){case "board"->verify(videos).board();case "preview"->verify(videos).preview(URL);case "create"->verify(videos).save(null,new Save(URL,true,5));
            case "edit"->verify(videos).save(id,new Save(URL,true,5));case "publish"->verify(videos).publish(id,new Publication(false,5));
            case "order"->verify(videos).reorder(new Order(List.of(id),5));case "recheck"->verify(videos).recheck(id,5);}
        verifyNoMoreInteractions(videos);assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }
    @ParameterizedTest @ValueSource(strings={"malformed","negative","uuid"})
    void given__invalid_body_revision_or_id__when__admin_binds__then__retain_source_400_without_business(String kind)throws Exception {
        var call=kind.equals("uuid")?put(ROOT+"/bad-id"):post(ROOT);
        String body=kind.equals("malformed")?"{":"{\"url\":\""+URL+"\",\"revision\":"+(kind.equals("negative")?"-1":"5")+"}";
        mvc.perform(call.header("Authorization",admin()).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isBadRequest())
                .andExpect(content().json("{\"code\":400,\"message\":\"영상 주소와 요청 형식을 확인해 주세요.\",\"data\":null}",JsonCompareMode.STRICT));verifyNoInteractions(videos);
    }
    @ParameterizedTest @ValueSource(strings={"missing","user"})
    void given__unverified_or_nonadmin_identity__when__posting_invalid_body_with_admin_header__then__reject_before_binding(String mode)throws Exception {
        var call=post(ROOT+"/preview").header("X-User-Role","ROLE_ADMIN").contentType(MediaType.APPLICATION_JSON).content("{");
        if(mode.equals("user"))call.header("Authorization","Bearer "+F3TestInputs.token(7,"ROLE_USER"));
        mvc.perform(call).andExpect(status().is(mode.equals("missing")?401:403));verifyNoInteractions(videos);assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }
    static Stream<Arguments> failures(){return Stream.of(Arguments.of(new ResponseStatusException(HttpStatus.BAD_REQUEST,"고정400"),400,"고정400"),
            Arguments.of(new ResponseStatusException(HttpStatus.NOT_FOUND,"영상을 찾지 못했습니다."),404,"영상을 찾지 못했습니다."),
            Arguments.of(new ResponseStatusException(HttpStatus.CONFLICT,"다른 관리자가 변경했습니다. 목록을 다시 불러와 주세요."),409,"다른 관리자가 변경했습니다. 목록을 다시 불러와 주세요."),
            Arguments.of(new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"YouTube 정보를 확인하지 못했습니다. 잠시 후 다시 시도해 주세요."),503,"YouTube 정보를 확인하지 못했습니다. 잠시 후 다시 시도해 주세요."),
            Arguments.of(new org.springframework.dao.DataAccessResourceFailureException("synthetic-private-key SQL"),503,"영상을 처리하지 못했습니다. 잠시 후 다시 시도해 주세요."));}
    @ParameterizedTest @MethodSource("failures")
    void given__source_business_or_unexpected_failure__when__an_admin_reads__then__retain_exact_safe_error_family(RuntimeException failure,int code,String message)throws Exception {
        when(videos.board()).thenThrow(failure);mvc.perform(get(ROOT).header("Authorization",admin())).andExpect(status().is(code))
                .andExpect(content().json("{\"code\":"+code+",\"message\":\""+message+"\",\"data\":null}",JsonCompareMode.STRICT));
    }
    @Test void given__unported_method_or_deeper_path__when__admin_requests__then__keep_default_deny()throws Exception {
        mvc.perform(delete(ROOT+"/"+ID).header("Authorization",admin())).andExpect(status().isForbidden());
        mvc.perform(post(ROOT+"/"+ID+"/recheck/extra").header("Authorization",admin())).andExpect(status().isForbidden());verifyNoInteractions(videos);
    }
    static String admin(){return "Bearer "+F3TestInputs.token(7,"ROLE_ADMIN");}
    static final String BOARD="""
        {"code":200,"message":"요청이 성공적으로 처리되었습니다.","data":{"revision":6,"videos":[{
        "id":"00000000-0000-0000-0000-000000000001","videoId":"AbCdEfGhI_1","published":true,"position":0,
        "createdAt":"2030-01-01T00:00:00.123456Z","title":null,"channelTitle":null,"thumbnailUrl":null,"durationSeconds":null,"available":false,"checkedAt":null}]}}
        """;
}
