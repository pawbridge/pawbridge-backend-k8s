package com.pawbridge.backend.animal.internal.sheltercommand.web

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.pawbridge.backend.animal.internal.sheltercommand.application.ShelterCommandConflictException
import com.pawbridge.backend.animal.internal.sheltercommand.application.ShelterCommandForbiddenException
import com.pawbridge.backend.animal.internal.sheltercommand.application.ShelterCommands
import io.mockk.confirmVerified
import io.mockk.every
import io.mockk.justRun
import io.mockk.mockk
import io.mockk.verify
import jakarta.persistence.EntityNotFoundException
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.core.MethodParameter
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.bind.support.WebDataBinderFactory
import org.springframework.web.context.request.NativeWebRequest
import org.springframework.web.method.support.HandlerMethodArgumentResolver
import org.springframework.web.method.support.ModelAndViewContainer
import java.nio.charset.StandardCharsets
import java.time.LocalDateTime

class ShelterCommandControllerTest {
    private val shelters: ShelterCommands = mockk<ShelterCommands>()
    private val mapper: ObjectMapper = ObjectMapper()
        .registerModule(JavaTimeModule())
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
    private var principal: Long? = 10L
    private val mvc: MockMvc = MockMvcBuilders.standaloneSetup(ShelterCommandController(shelters))
        .setControllerAdvice(ShelterCommandExceptionHandler())
        .setMessageConverters(MappingJackson2HttpMessageConverter(mapper))
        .setCustomArgumentResolvers(
            object : HandlerMethodArgumentResolver {
                override fun supportsParameter(p: MethodParameter): Boolean {
                    return p.hasParameterAnnotation(AuthenticationPrincipal::class.java)
                }

                override fun resolveArgument(
                    p: MethodParameter,
                    c: ModelAndViewContainer?,
                    q: NativeWebRequest,
                    b: WebDataBinderFactory?,
                ): Any? {
                    return principal
                }
            },
        )
        .build()
    private val now: LocalDateTime = LocalDateTime.of(2026, 10, 21, 3, 0)
    private val result = ShelterCommands.Details(
        7L, "care-7", "센터", "02", "서울", "대표", "기관", "a@example.test", "소개", "절차", "09-18", now, now,
    )

    @Test
    fun `전체 생성 요청일 때__POST하면__검증된 주체를 쓰고 원래의 201과 13개 필드를 돌려준다`() {
        // given
        every { shelters.create(any(), any()) } returns result

        // when
        val response = mvc.perform(
            post("/api/v1/shelters")
                .header("X-User-Id", "999")
                .header("X-User-Role", "ROLE_ADMIN")
                .contentType("application/json")
                .content(
                    """
                    {"careRegNo":"care-7","name":"센터","phone":"02","address":"서울","ownerName":"대표","organizationName":"기관",
                     "email":"a@example.test","introduction":"소개","adoptionProcedure":"절차","operatingHours":"09-18"}
                    """.trimIndent(),
                ),
        ).andExpect(status().isCreated()).andReturn().response

        // then
        val body: JsonNode = mapper.readTree(response.getContentAsString(StandardCharsets.UTF_8))
        assertThat(body.size()).isEqualTo(13)
        assertThat(body.path("id").asLong()).isEqualTo(7L)
        assertThat(body.path("createdAt").asText()).isEqualTo("2026-10-21T03:00:00")
        assertThat(body.has("publicInformation")).isFalse()
        verify(exactly = 1) {
            shelters.create(
                10L,
                ShelterCommands.Create("care-7", "센터", "02", "서울", "대표", "기관", "a@example.test", "소개", "절차", "09-18"),
            )
        }
    }

    @Test
    fun `빈 PUT 요청일 때__수정하면__null 다섯 개를 전달하고 null 응답 필드는 생략한다`() {
        // given
        every { shelters.update(any(), any(), any()) } returns
            ShelterCommands.Details(7L, "care-7", "센터", null, null, null, null, null, null, null, null, now, null)

        // when
        val body = mapper.readTree(
            mvc.perform(put("/api/v1/shelters/7").contentType("application/json").content("{}"))
                .andExpect(status().isOk()).andReturn().response.getContentAsString(StandardCharsets.UTF_8),
        )

        // then
        assertThat(body.has("phone")).isFalse()
        assertThat(body.has("updatedAt")).isFalse()
        verify(exactly = 1) { shelters.update(10L, 7L, ShelterCommands.Update(null, null, null, null, null)) }
    }

    @Test
    fun `유효한 삭제 요청일 때__삭제하면__본문 없이 204를 돌려준다`() {
        // given
        justRun { shelters.delete(any(), any()) }

        // when
        val response = mvc.perform(delete("/api/v1/shelters/7")).andExpect(status().isNoContent()).andReturn().response

        // then
        assertThat(response.contentAsByteArray).isEmpty()
        verify(exactly = 1) { shelters.delete(10L, 7L) }
    }

    @Test
    fun `주체 없이 헤더만 위조됐을 때__삭제하면__헤더의 신원이나 역할을 쓰지 않는다`() {
        // given
        principal = null

        every { shelters.delete(any(), any()) } throws ShelterCommandForbiddenException()

        // when
        val result = mvc.perform(delete("/api/v1/shelters/7").header("X-User-Id", "10").header("X-User-Role", "ROLE_ADMIN"))

        // then
        result.andExpect(status().isForbidden())
        verify(exactly = 1) { shelters.delete(null, 7L) }
    }

    @Test
    fun `참조 충돌이 있을 때__삭제하면__고정된 409 본문을 돌려준다`() {
        // given
        every { shelters.delete(any(), any()) } throws ShelterCommandConflictException()

        // when
        val body = mapper.readTree(
            mvc.perform(delete("/api/v1/shelters/7")).andExpect(status().isConflict()).andReturn().response
                .getContentAsString(StandardCharsets.UTF_8),
        )

        // then
        assertThat(body.path("code").asInt()).isEqualTo(409)
        assertThat(body.path("message").asText()).isEqualTo(ShelterCommandConflictException().message)
        assertThat(body.path("data").isNull()).isTrue()
        verify(exactly = 1) { shelters.delete(10L, 7L) }
    }

    @Test
    fun `엔티티가 없을 때__삭제하면__내부 메시지 없이 원래의 500을 유지한다`() {
        // given
        every { shelters.delete(any(), any()) } throws EntityNotFoundException("secret-table-detail")

        // when
        val response = mvc.perform(delete("/api/v1/shelters/7"))
            .andExpect(status().isInternalServerError()).andReturn().response

        // then
        assertThat(response.contentAsString).doesNotContain("secret-table-detail")
        verify(exactly = 1) { shelters.delete(10L, 7L) }
    }

    @ParameterizedTest
    @ValueSource(strings = ["{broken", "[]"])
    fun `본문이 잘못됐을 때__생성하면__서비스를 호출하지 않고 400을 돌려준다`(body: String) {
        // given
        // when
        val result = mvc.perform(post("/api/v1/shelters").contentType("application/json").content(body))

        // then
        result.andExpect(status().isBadRequest())
        confirmVerified(shelters)
    }
}
