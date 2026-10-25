package com.pawbridge.backend

import com.pawbridge.backend.animal.internal.image.infrastructure.AnimalImageForbiddenException
import com.pawbridge.backend.animal.internal.image.infrastructure.AnimalImageService
import com.pawbridge.backend.animal.internal.image.infrastructure.AnimalImageTooLargeException
import com.pawbridge.backend.animal.internal.image.web.AnimalImageController
import com.pawbridge.backend.animal.internal.image.web.AnimalImageExceptionHandler
import com.pawbridge.backend.boot.configuration.ReadSecurityConfiguration
import com.pawbridge.backend.support.anyNonNull
import com.pawbridge.backend.support.argThatValue
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.MethodSource
import org.junit.jupiter.params.provider.ValueSource
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.reset
import org.mockito.Mockito.`when` as whenever
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.verifyNoMoreInteractions
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.dao.DataAccessResourceFailureException
import org.springframework.http.HttpMethod
import org.springframework.mock.web.MockMultipartFile
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.test.context.ContextConfiguration
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.web.multipart.MultipartFile
import java.util.stream.Stream

/** Actual ReadSecurity/JWT filter and scoped advice; only the image service is mocked. */
@WebMvcTest(
    properties = ["pawbridge.animal-images.enabled=TrUe"],
    excludeAutoConfiguration = [UserDetailsServiceAutoConfiguration::class],
)
@ContextConfiguration(
    classes = [
        AnimalImageController::class,
        AnimalImageExceptionHandler::class,
        ReadSecurityConfiguration::class,
        F3TestInputs.SecurityInputs::class,
    ],
)
class AnimalImageHttpTest {
    @Autowired
    lateinit var mvc: MockMvc

    @MockitoBean
    lateinit var images: AnimalImageService

    @AfterEach
    fun given__request_finished__when__security_scope_checked__then__retain_no_principal() {
        assertThat(SecurityContextHolder.getContext().authentication).isNull()
    }

    @ParameterizedTest
    @ValueSource(strings = ["ROLE_ADMIN", "ROLE_SHELTER", "ROLE_USER"])
    fun given__signed_member_and_forged_headers__when__uploading_one_image__then__pass_only_verified_id_and_return_original_map(
        role: String,
    ) {
        whenever(images.uploadImage(eq(7L), anyNonNull<MultipartFile>())).thenReturn("https://assets.invalid/animals/a.png")
        val result = mvc.perform(call("single").header("Authorization", "Bearer " + F3TestInputs.token(7, role)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.imageUrl").value("https://assets.invalid/animals/a.png"))
            .andExpect(jsonPath("$.length()").value(1))
            .andExpect(header().doesNotExist("Set-Cookie"))
        verify(images).uploadImage(
            eq(7L),
            argThatValue<MultipartFile> { file -> "photo.png" == file.originalFilename && file.size == 3L },
        )
        assertThat(result.andReturn().request.getSession(false)).isNull()
        verifyNoMoreInteractions(images)
    }

    @Test
    fun given__signed_user__when__uploading_multiple_images__then__reach_the_original_unscoped_route() {
        whenever(images.uploadImages(anyNonNull<Array<MultipartFile?>>())).thenReturn(listOf("u1", "u2"))
        mvc.perform(call("multiple").header("Authorization", "Bearer " + F3TestInputs.token(7, "ROLE_USER")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.imageUrls[0]").value("u1"))
            .andExpect(jsonPath("$.imageUrls[1]").value("u2"))
            .andExpect(jsonPath("$.count").value(2))
            .andExpect(jsonPath("$.length()").value(2))
        verify(images).uploadImages(
            argThatValue<Array<MultipartFile?>> { files ->
                files.size == 2 && "a.png" == files[0]!!.originalFilename && "b.png" == files[1]!!.originalFilename
            },
        )
        verifyNoMoreInteractions(images)
    }

    @Test
    fun given__signed_member__when__deleting_an_image_url__then__return_the_original_message() {
        mvc.perform(call("delete").header("Authorization", "Bearer " + F3TestInputs.token(7, "ROLE_SHELTER")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.message").value("이미지가 삭제되었습니다."))
            .andExpect(jsonPath("$.length()").value(1))
        verify(images).deleteImage(7L, "https://assets.invalid/products/a.png")
        verifyNoMoreInteractions(images)
    }

    @ParameterizedTest
    @MethodSource("deniedCredentials")
    fun given__missing_or_invalid_jwt_with_forged_headers__when__using_each_route__then__deny_before_business(
        route: String,
        token: String,
    ) {
        val request = call(route)
        if (token == "invalid") {
            request.header("Authorization", "Bearer invalid-token")
        }
        mvc.perform(request).andExpect(status().isUnauthorized()).andExpect(header().doesNotExist("Set-Cookie"))
        verifyNoInteractions(images)
    }

    @Test
    fun given__business_refusals__when__advice_handles_them__then__preserve_original_status_and_body_shape() {
        val admin = "Bearer " + F3TestInputs.token(7, "ROLE_ADMIN")
        whenever(images.uploadImage(eq(7L), anyNonNull<MultipartFile>())).thenThrow(AnimalImageForbiddenException())
        mvc.perform(call("single").header("Authorization", admin))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value(403))
            .andExpect(jsonPath("$.message").value("권한이 부족합니다."))
            .andExpect(jsonPath("$.data").isEmpty())
        reset(images)
        whenever(images.uploadImage(eq(7L), anyNonNull<MultipartFile>())).thenThrow(AnimalImageTooLargeException())
        mvc.perform(call("single").header("Authorization", admin))
            .andExpect(status().isPayloadTooLarge())
            .andExpect(jsonPath("$.code").value(413))
            .andExpect(jsonPath("$.message").value("사진 크기 제한을 초과했습니다"))
        reset(images)
        whenever(images.uploadImages(anyNonNull<Array<MultipartFile?>>()))
            .thenThrow(IllegalArgumentException("최대 5개의 이미지만 업로드할 수 있습니다."))
        mvc.perform(call("multiple").header("Authorization", admin))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value(400))
            .andExpect(jsonPath("$.message").value("최대 5개의 이미지만 업로드할 수 있습니다."))
        reset(images)
        whenever(images.uploadImage(eq(7L), anyNonNull<MultipartFile>())).thenThrow(RuntimeException("이미지 업로드에 실패했습니다."))
        mvc.perform(call("single").header("Authorization", admin))
            .andExpect(status().isInternalServerError())
            .andExpect(jsonPath("$.code").value(500))
            .andExpect(jsonPath("$.message").value("서버 내부 오류가 발생했습니다."))
        reset(images)
        doThrow(DataAccessResourceFailureException("synthetic")).`when`(images).deleteImage(eq(7L), anyNonNull<String>())
        mvc.perform(call("delete").header("Authorization", admin))
            .andExpect(status().isInternalServerError())
            .andExpect(jsonPath("$.message").value("데이터베이스 오류가 발생했습니다."))
    }

    @Test
    fun given__missing_request_values__when__calling_routes__then__keep_original_missing_parameter_and_part_outcomes() {
        val admin = "Bearer " + F3TestInputs.token(7, "ROLE_ADMIN")
        mvc.perform(delete("/api/v1/animals/images").header("Authorization", admin))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value("필수 요청 파라미터 'imageUrl'(이)가 누락되었습니다."))
        mvc.perform(
            multipart("/api/v1/animals/images")
                .file(MockMultipartFile("other", "a.png", "image/png", byteArrayOf(1)))
                .header("Authorization", admin),
        )
            .andExpect(status().isInternalServerError())
            .andExpect(jsonPath("$.message").value("서버 내부 오류가 발생했습니다."))
        mvc.perform(
            multipart("/api/v1/animals/images/multiple")
                .file(MockMultipartFile("other", "a.png", "image/png", byteArrayOf(1)))
                .header("Authorization", admin),
        ).andExpect(status().isInternalServerError())
        verifyNoInteractions(images)
    }

    @ParameterizedTest
    @CsvSource(
        "POST,/api/animals/images",
        "POST,/api/animals/images/multiple",
        "DELETE,/api/animals/images",
        "GET,/api/v1/animals/images",
        "PUT,/api/v1/animals/images",
        "DELETE,/api/v1/animals/images/multiple",
        "POST,/api/v1/animals/images/extra",
        "GET,/api/v1/animals/images/multiple",
    )
    fun given__signed_admin__when__using_gateway_alias_or_unowned_neighbor__then__retain_default_deny(
        method: String,
        path: String,
    ) {
        mvc.perform(
            request(HttpMethod.valueOf(method), path)
                .header("Authorization", "Bearer " + F3TestInputs.token(7, "ROLE_ADMIN")),
        ).andExpect(status().isForbidden())
        verifyNoInteractions(images)
    }

    companion object {
        val ROUTES: List<String> = listOf("single", "multiple", "delete")

        @JvmStatic
        fun deniedCredentials(): Stream<Arguments> {
            return ROUTES.stream().flatMap { route ->
                Stream.of("missing", "invalid").map { token -> Arguments.of(route, token) }
            }
        }

        fun call(route: String): MockHttpServletRequestBuilder {
            return when (route) {
                "single" -> multipart("/api/v1/animals/images")
                    .file(MockMultipartFile("file", "photo.png", "image/png", byteArrayOf(1, 2, 3)))
                    .header("X-User-Id", "99")
                    .header("X-User-Role", "ROLE_ADMIN")
                "multiple" -> multipart("/api/v1/animals/images/multiple")
                    .file(MockMultipartFile("files", "a.png", "image/png", byteArrayOf(1)))
                    .file(MockMultipartFile("files", "b.png", "image/png", byteArrayOf(2)))
                    .header("X-User-Id", "99")
                    .header("X-User-Role", "ROLE_ADMIN")
                "delete" -> delete("/api/v1/animals/images")
                    .param("imageUrl", "https://assets.invalid/products/a.png")
                    .header("X-User-Id", "99")
                    .header("X-User-Role", "ROLE_ADMIN")
                else -> throw AssertionError("Unknown image route")
            }
        }
    }
}

@WebMvcTest(excludeAutoConfiguration = [UserDetailsServiceAutoConfiguration::class])
@ContextConfiguration(
    classes = [
        AnimalImageController::class,
        AnimalImageExceptionHandler::class,
        ReadSecurityConfiguration::class,
        F3TestInputs.SecurityInputs::class,
    ],
)
class AnimalImageDisabledHttpTest {
    @Autowired
    lateinit var mvc: MockMvc

    @MockitoBean
    lateinit var images: AnimalImageService

    @ParameterizedTest
    @ValueSource(strings = ["single", "multiple", "delete"])
    fun given__no_image_flag__when__verified_admin_uses_an_image_route__then__deny_before_business(route: String) {
        mvc.perform(AnimalImageHttpTest.call(route).header("Authorization", "Bearer " + F3TestInputs.token(7, "ROLE_ADMIN")))
            .andExpect(status().isForbidden())
        verifyNoInteractions(images)
    }
}

@WebMvcTest(
    properties = ["pawbridge.animal-images.enabled=on"],
    excludeAutoConfiguration = [UserDetailsServiceAutoConfiguration::class],
)
@ContextConfiguration(
    classes = [
        AnimalImageController::class,
        AnimalImageExceptionHandler::class,
        ReadSecurityConfiguration::class,
        F3TestInputs.SecurityInputs::class,
    ],
)
class AnimalImageAliasDisabledHttpTest {
    @Autowired
    lateinit var mvc: MockMvc

    @MockitoBean
    lateinit var images: AnimalImageService

    @ParameterizedTest
    @ValueSource(strings = ["single", "multiple", "delete"])
    fun given__unowned_boolean_alias__when__verified_admin_uses_an_image_route__then__keep_the_owner_flag_closed(
        route: String,
    ) {
        mvc.perform(AnimalImageHttpTest.call(route).header("Authorization", "Bearer " + F3TestInputs.token(7, "ROLE_ADMIN")))
            .andExpect(status().isForbidden())
        verifyNoInteractions(images)
    }
}
