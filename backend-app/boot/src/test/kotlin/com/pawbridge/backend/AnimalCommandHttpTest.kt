package com.pawbridge.backend

import com.ninjasquad.springmockk.MockkBean
import com.pawbridge.backend.animal.internal.command.infrastructure.dto.response.AnimalDetailResponse
import com.pawbridge.backend.animal.internal.command.infrastructure.enums.Species
import com.pawbridge.backend.animal.internal.command.infrastructure.service.AnimalCommandService
import com.pawbridge.backend.animal.internal.command.infrastructure.service.AnimalNotEditableException
import com.pawbridge.backend.animal.internal.command.web.AnimalCommandController
import com.pawbridge.backend.animal.internal.command.web.AnimalCommandExceptionHandler
import com.pawbridge.backend.boot.configuration.ReadSecurityConfiguration
import io.mockk.confirmVerified
import io.mockk.every
import io.mockk.verify
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.http.MediaType
import org.springframework.test.context.ContextConfiguration
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

@WebMvcTest(
    properties = ["pawbridge.animal-commands.enabled=true"],
    excludeAutoConfiguration = [UserDetailsServiceAutoConfiguration::class],
)
@ContextConfiguration(
    classes = [
        AnimalCommandController::class,
        AnimalCommandExceptionHandler::class,
        ReadSecurityConfiguration::class,
        F3TestInputs.SecurityInputs::class,
    ],
)
class AnimalCommandHttpTest {
    @Autowired
    lateinit var mvc: MockMvc

    @MockkBean
    lateinit var animals: AnimalCommandService

    private val token = "Bearer " + F3TestInputs.token(11, "ROLE_SHELTER")

    @Test
    fun `직접 등록한 동물일 때__정보 수정을 보내면__JWT 사용자로 수정하고 수정된 상세를 준다`() {
        // given
        every { animals.update(any(), any(), any()) } returns AnimalDetailResponse.builder().id(42L).species(Species.CAT).build()

        // when
        val result = mvc.perform(put(PATH).header("Authorization", token).contentType(MediaType.APPLICATION_JSON).content(BODY))

        // then
        result
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value(42))
            .andExpect(jsonPath("$.species").value("CAT"))
        verify(exactly = 1) {
            animals.update(11L, 42L, match { it.species == Species.CAT && it.breed == null && it.description == "고친 설명" })
        }
        confirmVerified(animals)
    }

    @Test
    fun `APMS 동물일 때__정보 수정을 보내면__409와 수정 불가 안내를 준다`() {
        // given
        every { animals.update(any(), any(), any()) } throws AnimalNotEditableException()

        // when
        val result = mvc.perform(put(PATH).header("Authorization", token).contentType(MediaType.APPLICATION_JSON).content(BODY))

        // then
        result
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value(409))
            .andExpect(jsonPath("$.message").value("보호소가 직접 등록한 동물만 수정할 수 있습니다."))
    }

    @Test
    fun `필수 항목이 빠졌을 때__정보 수정을 보내면__서비스를 부르지 않고 400을 준다`() {
        // given
        val withoutSpecies = BODY.replace("\"species\":\"CAT\",", "")

        // when
        val result = mvc.perform(put(PATH).header("Authorization", token).contentType(MediaType.APPLICATION_JSON).content(withoutSpecies))

        // then
        result
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value("species: 축종은 필수입니다"))
        confirmVerified(animals)
    }

    companion object {
        private const val PATH = "/api/v1/animals/42"
        private const val BODY = """{"species":"CAT","gender":"FEMALE","neuterStatus":"YES",""" +
            """"noticeStartDate":"2026-10-21","noticeEndDate":"2026-11-05","description":"고친 설명"}"""
    }
}
