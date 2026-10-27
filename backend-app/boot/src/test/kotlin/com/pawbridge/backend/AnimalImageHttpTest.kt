package com.pawbridge.backend

import com.ninjasquad.springmockk.MockkBean
import com.pawbridge.backend.animal.internal.image.infrastructure.AnimalImageForbiddenException
import com.pawbridge.backend.animal.internal.image.infrastructure.AnimalImageService
import com.pawbridge.backend.animal.internal.image.infrastructure.AnimalImageTooLargeException
import com.pawbridge.backend.animal.internal.image.web.AnimalImageController
import com.pawbridge.backend.animal.internal.image.web.AnimalImageExceptionHandler
import com.pawbridge.backend.boot.configuration.ReadSecurityConfiguration
import io.mockk.confirmVerified
import io.mockk.every
import io.mockk.justRun
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.dao.DataAccessResourceFailureException
import org.springframework.mock.web.MockMultipartFile
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.test.context.ContextConfiguration
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
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

    @MockkBean
    lateinit var images: AnimalImageService

    @AfterEach
    fun given__request_finished__when__security_scope_checked__then__retain_no_principal() {
        assertThat(SecurityContextHolder.getContext().authentication).isNull()
    }

    @ParameterizedTest
    @ValueSource(strings = ["ROLE_ADMIN", "ROLE_SHELTER", "ROLE_USER"])
    fun `서명된 회원과 위조 헤더__이미지 한 장을 올리면__검증된 ID만 넘기고 원래 맵을 돌려준다`(
        role: String,
    ) {
        // given
        every { images.uploadImage(any(), any()) } returns "https://assets.invalid/animals/a.png"

        // when
        val result = mvc.perform(call("single").header("Authorization", "Bearer " + F3TestInputs.token(7, role)))

        // then
        result
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.imageUrl").value("https://assets.invalid/animals/a.png"))
            .andExpect(jsonPath("$.length()").value(1))
            .andExpect(header().doesNotExist("Set-Cookie"))
        verify(exactly = 1) {
            images.uploadImage(
                7L,
                match { file -> file != null && "photo.png" == file.originalFilename && file.size == 3L },
            )
        }
        assertThat(result.andReturn().request.getSession(false)).isNull()
        confirmVerified(images)
    }

    @Test
    fun `서명된 회원과 위조 헤더__이미지 여러 장을 올리면__검증된 ID만 넘기고 원래 맵을 돌려준다`() {
        // given
        every { images.uploadImages(any(), any()) } returns listOf("u1", "u2")

        // when
        val result = mvc.perform(call("multiple").header("Authorization", "Bearer " + F3TestInputs.token(7, "ROLE_USER")))

        // then
        result
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.imageUrls[0]").value("u1"))
            .andExpect(jsonPath("$.imageUrls[1]").value("u2"))
            .andExpect(jsonPath("$.count").value(2))
            .andExpect(jsonPath("$.length()").value(2))
        verify(exactly = 1) {
            images.uploadImages(
                7L,
                match { files ->
                    files != null &&
                        files.size == 2 &&
                        "a.png" == files[0]!!.originalFilename &&
                        "b.png" == files[1]!!.originalFilename
                },
            )
        }
        confirmVerified(images)
    }

    @Test
    fun `공개 삭제 경로를 없앴을 때__익명이나 서명된 보호소·관리자가 삭제를 보내면__403이고 서비스를 부르지 않는다`() {
        // given
        val anyObject = "https://assets.invalid/products/a.png"

        // when
        val anonymous = mvc.perform(delete("/api/v1/animals/images").param("imageUrl", anyObject))
        val shelter = mvc.perform(
            delete("/api/v1/animals/images")
                .param("imageUrl", anyObject)
                .header("Authorization", "Bearer " + F3TestInputs.token(7, "ROLE_SHELTER")),
        )
        val admin = mvc.perform(
            delete("/api/v1/animals/images")
                .param("imageUrl", anyObject)
                .header("Authorization", "Bearer " + F3TestInputs.token(7, "ROLE_ADMIN")),
        )

        // then
        anonymous.andExpect(status().isForbidden())
        shelter.andExpect(status().isForbidden())
        admin.andExpect(status().isForbidden())
        confirmVerified(images)
    }

    @Test
    fun `업무 거부__어드바이스가 처리하면__원래 상태와 본문 형태를 유지한다`() {
        // given
        val admin = "Bearer " + F3TestInputs.token(7, "ROLE_ADMIN")

        every { images.uploadImage(any(), any()) } throwsMany listOf(
            AnimalImageForbiddenException(),
            AnimalImageTooLargeException(),
            RuntimeException("이미지 업로드에 실패했습니다."),
        )
        every { images.uploadImages(any(), any()) } throwsMany listOf(
            IllegalArgumentException("최대 5개의 이미지만 업로드할 수 있습니다."),
            DataAccessResourceFailureException("synthetic"),
        )

        // when
        val forbidden = mvc.perform(call("single").header("Authorization", admin))
        val tooLarge = mvc.perform(call("single").header("Authorization", admin))
        val badRequest = mvc.perform(call("multiple").header("Authorization", admin))
        val runtimeFailure = mvc.perform(call("single").header("Authorization", admin))
        val databaseFailure = mvc.perform(call("multiple").header("Authorization", admin))

        // then
        forbidden
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value(403))
            .andExpect(jsonPath("$.message").value("권한이 부족합니다."))
            .andExpect(jsonPath("$.data").isEmpty())
        tooLarge
            .andExpect(status().isPayloadTooLarge())
            .andExpect(jsonPath("$.code").value(413))
            .andExpect(jsonPath("$.message").value("사진 크기 제한을 초과했습니다"))
        badRequest
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value(400))
            .andExpect(jsonPath("$.message").value("최대 5개의 이미지만 업로드할 수 있습니다."))
        runtimeFailure
            .andExpect(status().isInternalServerError())
            .andExpect(jsonPath("$.code").value(500))
            .andExpect(jsonPath("$.message").value("서버 내부 오류가 발생했습니다."))
        databaseFailure
            .andExpect(status().isInternalServerError())
            .andExpect(jsonPath("$.message").value("데이터베이스 오류가 발생했습니다."))
    }

    @Test
    fun `빠진 파트__업로드 경로를 호출하면__원래 파트 누락 결과를 유지한다`() {
        // given
        val admin = "Bearer " + F3TestInputs.token(7, "ROLE_ADMIN")

        // when
        val missingSinglePart = mvc.perform(
            multipart("/api/v1/animals/images")
                .file(MockMultipartFile("other", "a.png", "image/png", byteArrayOf(1)))
                .header("Authorization", admin),
        )
        val missingMultiplePart = mvc.perform(
            multipart("/api/v1/animals/images/multiple")
                .file(MockMultipartFile("other", "a.png", "image/png", byteArrayOf(1)))
                .header("Authorization", admin),
        )

        // then
        missingSinglePart
            .andExpect(status().isInternalServerError())
            .andExpect(jsonPath("$.message").value("서버 내부 오류가 발생했습니다."))
        missingMultiplePart.andExpect(status().isInternalServerError())
        confirmVerified(images)
    }

    companion object {
        val ROUTES: List<String> = listOf("single", "multiple")

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
                else -> throw AssertionError("Unknown image route")
            }
        }
    }
}
