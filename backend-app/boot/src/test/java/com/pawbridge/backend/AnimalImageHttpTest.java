package com.pawbridge.backend;

import com.pawbridge.backend.animal.internal.image.infrastructure.AnimalImageForbiddenException;
import com.pawbridge.backend.animal.internal.image.infrastructure.AnimalImageService;
import com.pawbridge.backend.animal.internal.image.infrastructure.AnimalImageTooLargeException;
import com.pawbridge.backend.animal.internal.image.web.AnimalImageController;
import com.pawbridge.backend.animal.internal.image.web.AnimalImageExceptionHandler;
import com.pawbridge.backend.boot.configuration.ReadSecurityConfiguration;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpMethod;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.multipart.MultipartFile;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Actual ReadSecurity/JWT filter and scoped advice; only the image service is mocked. */
@WebMvcTest(properties="pawbridge.animal-images.enabled=TrUe",
        excludeAutoConfiguration=org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration.class)
@ContextConfiguration(classes={AnimalImageController.class,AnimalImageExceptionHandler.class,
        ReadSecurityConfiguration.class,F3TestInputs.SecurityInputs.class})
class AnimalImageHttpTest {
    static final List<String> ROUTES=List.of("single","multiple","delete");
    @Autowired MockMvc mvc;
    @MockitoBean AnimalImageService images;

    static Stream<Arguments> deniedCredentials(){return ROUTES.stream().flatMap(route->Stream.of("missing","invalid").map(token->Arguments.of(route,token)));}

    @AfterEach void given__request_finished__when__security_scope_checked__then__retain_no_principal(){
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @ParameterizedTest @ValueSource(strings={"ROLE_ADMIN","ROLE_SHELTER","ROLE_USER"})
    void given__signed_member_and_forged_headers__when__uploading_one_image__then__pass_only_verified_id_and_return_original_map(String role) throws Exception {
        when(images.uploadImage(eq(7L),any())).thenReturn("https://assets.invalid/animals/a.png");
        var result=mvc.perform(call("single").header("Authorization","Bearer "+F3TestInputs.token(7,role)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.imageUrl").value("https://assets.invalid/animals/a.png"))
                .andExpect(jsonPath("$.length()").value(1)).andExpect(header().doesNotExist("Set-Cookie"));
        verify(images).uploadImage(eq(7L),argThat(file->"photo.png".equals(file.getOriginalFilename())&&file.getSize()==3));
        assertThat(result.andReturn().getRequest().getSession(false)).isNull();verifyNoMoreInteractions(images);
    }

    @Test void given__signed_user__when__uploading_multiple_images__then__reach_the_original_unscoped_route() throws Exception {
        when(images.uploadImages(any())).thenReturn(List.of("u1","u2"));
        mvc.perform(call("multiple").header("Authorization","Bearer "+F3TestInputs.token(7,"ROLE_USER")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.imageUrls[0]").value("u1")).andExpect(jsonPath("$.imageUrls[1]").value("u2"))
                .andExpect(jsonPath("$.count").value(2)).andExpect(jsonPath("$.length()").value(2));
        verify(images).uploadImages(argThat((MultipartFile[] files)->files.length==2&&"a.png".equals(files[0].getOriginalFilename())&&"b.png".equals(files[1].getOriginalFilename())));
        verifyNoMoreInteractions(images);
    }

    @Test void given__signed_member__when__deleting_an_image_url__then__return_the_original_message() throws Exception {
        mvc.perform(call("delete").header("Authorization","Bearer "+F3TestInputs.token(7,"ROLE_SHELTER")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.message").value("이미지가 삭제되었습니다.")).andExpect(jsonPath("$.length()").value(1));
        verify(images).deleteImage(7L,"https://assets.invalid/products/a.png");verifyNoMoreInteractions(images);
    }

    @ParameterizedTest @MethodSource("deniedCredentials")
    void given__missing_or_invalid_jwt_with_forged_headers__when__using_each_route__then__deny_before_business(String route,String token) throws Exception {
        var request=call(route);if(token.equals("invalid"))request.header("Authorization","Bearer invalid-token");
        mvc.perform(request).andExpect(status().isUnauthorized()).andExpect(header().doesNotExist("Set-Cookie"));
        verifyNoInteractions(images);
    }

    @Test void given__business_refusals__when__advice_handles_them__then__preserve_original_status_and_body_shape() throws Exception {
        String admin="Bearer "+F3TestInputs.token(7,"ROLE_ADMIN");
        when(images.uploadImage(eq(7L),any())).thenThrow(new AnimalImageForbiddenException());
        mvc.perform(call("single").header("Authorization",admin)).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403)).andExpect(jsonPath("$.message").value("권한이 부족합니다.")).andExpect(jsonPath("$.data").isEmpty());
        reset(images);when(images.uploadImage(eq(7L),any())).thenThrow(new AnimalImageTooLargeException());
        mvc.perform(call("single").header("Authorization",admin)).andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.code").value(413)).andExpect(jsonPath("$.message").value("사진 크기 제한을 초과했습니다"));
        reset(images);when(images.uploadImages(any())).thenThrow(new IllegalArgumentException("최대 5개의 이미지만 업로드할 수 있습니다."));
        mvc.perform(call("multiple").header("Authorization",admin)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400)).andExpect(jsonPath("$.message").value("최대 5개의 이미지만 업로드할 수 있습니다."));
        reset(images);when(images.uploadImage(eq(7L),any())).thenThrow(new RuntimeException("이미지 업로드에 실패했습니다."));
        mvc.perform(call("single").header("Authorization",admin)).andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value(500)).andExpect(jsonPath("$.message").value("서버 내부 오류가 발생했습니다."));
        reset(images);doThrow(new DataAccessResourceFailureException("synthetic")).when(images).deleteImage(eq(7L),any());
        mvc.perform(call("delete").header("Authorization",admin)).andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.message").value("데이터베이스 오류가 발생했습니다."));
    }

    @Test void given__missing_request_values__when__calling_routes__then__keep_original_missing_parameter_and_part_outcomes() throws Exception {
        String admin="Bearer "+F3TestInputs.token(7,"ROLE_ADMIN");
        mvc.perform(delete("/api/v1/animals/images").header("Authorization",admin)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("필수 요청 파라미터 'imageUrl'(이)가 누락되었습니다."));
        mvc.perform(multipart("/api/v1/animals/images").file(new MockMultipartFile("other","a.png","image/png",new byte[]{1})).header("Authorization",admin))
                .andExpect(status().isInternalServerError()).andExpect(jsonPath("$.message").value("서버 내부 오류가 발생했습니다."));
        mvc.perform(multipart("/api/v1/animals/images/multiple").file(new MockMultipartFile("other","a.png","image/png",new byte[]{1})).header("Authorization",admin))
                .andExpect(status().isInternalServerError());
        verifyNoInteractions(images);
    }

    @ParameterizedTest @CsvSource({"POST,/api/animals/images","POST,/api/animals/images/multiple","DELETE,/api/animals/images",
            "GET,/api/v1/animals/images","PUT,/api/v1/animals/images","DELETE,/api/v1/animals/images/multiple",
            "POST,/api/v1/animals/images/extra","GET,/api/v1/animals/images/multiple"})
    void given__signed_admin__when__using_gateway_alias_or_unowned_neighbor__then__retain_default_deny(String method,String path) throws Exception {
        mvc.perform(request(HttpMethod.valueOf(method),path).header("Authorization","Bearer "+F3TestInputs.token(7,"ROLE_ADMIN")))
                .andExpect(status().isForbidden());
        verifyNoInteractions(images);
    }

    static MockHttpServletRequestBuilder call(String route){
        return switch(route){
            case "single"->multipart("/api/v1/animals/images").file(new MockMultipartFile("file","photo.png","image/png",new byte[]{1,2,3}))
                    .header("X-User-Id","99").header("X-User-Role","ROLE_ADMIN");
            case "multiple"->multipart("/api/v1/animals/images/multiple")
                    .file(new MockMultipartFile("files","a.png","image/png",new byte[]{1})).file(new MockMultipartFile("files","b.png","image/png",new byte[]{2}))
                    .header("X-User-Id","99").header("X-User-Role","ROLE_ADMIN");
            case "delete"->delete("/api/v1/animals/images").param("imageUrl","https://assets.invalid/products/a.png")
                    .header("X-User-Id","99").header("X-User-Role","ROLE_ADMIN");
            default->throw new AssertionError("Unknown image route");
        };
    }
}

@WebMvcTest(excludeAutoConfiguration=org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration.class)
@ContextConfiguration(classes={AnimalImageController.class,AnimalImageExceptionHandler.class,ReadSecurityConfiguration.class,F3TestInputs.SecurityInputs.class})
class AnimalImageDisabledHttpTest {
    @Autowired MockMvc mvc;
    @MockitoBean AnimalImageService images;

    @ParameterizedTest @ValueSource(strings={"single","multiple","delete"})
    void given__no_image_flag__when__verified_admin_uses_an_image_route__then__deny_before_business(String route) throws Exception {
        mvc.perform(AnimalImageHttpTest.call(route).header("Authorization","Bearer "+F3TestInputs.token(7,"ROLE_ADMIN")))
                .andExpect(status().isForbidden());
        verifyNoInteractions(images);
    }
}

@WebMvcTest(properties="pawbridge.animal-images.enabled=on",
        excludeAutoConfiguration=org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration.class)
@ContextConfiguration(classes={AnimalImageController.class,AnimalImageExceptionHandler.class,ReadSecurityConfiguration.class,F3TestInputs.SecurityInputs.class})
class AnimalImageAliasDisabledHttpTest {
    @Autowired MockMvc mvc;
    @MockitoBean AnimalImageService images;

    @ParameterizedTest @ValueSource(strings={"single","multiple","delete"})
    void given__unowned_boolean_alias__when__verified_admin_uses_an_image_route__then__keep_the_owner_flag_closed(String route) throws Exception {
        mvc.perform(AnimalImageHttpTest.call(route).header("Authorization","Bearer "+F3TestInputs.token(7,"ROLE_ADMIN")))
                .andExpect(status().isForbidden());
        verifyNoInteractions(images);
    }
}
