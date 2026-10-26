package com.pawbridge.backend

import com.ninjasquad.springmockk.MockkBean
import com.pawbridge.backend.animal.internal.travel.application.PetTravel
import com.pawbridge.backend.animal.internal.travel.web.PetTravelController
import com.pawbridge.backend.boot.configuration.ReadSecurityConfiguration
import io.mockk.confirmVerified
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.http.HttpMethod
import org.springframework.test.context.ContextConfiguration
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

@WebMvcTest(excludeAutoConfiguration = [UserDetailsServiceAutoConfiguration::class])
@ContextConfiguration(
    classes = [PetTravelController::class, ReadSecurityConfiguration::class, F3TestInputs.SecurityInputs::class],
)
class AnimalTravelDisabledHttpTest {
    @Autowired
    lateinit var mvc: MockMvc

    @MockkBean
    lateinit var travel: PetTravel

    @ParameterizedTest
    @CsvSource("/api/v1/places/regions", "/api/v1/places", "/api/v1/places/7")
    fun `여행이 꺼졌을 때__공개 조회를 요청하면__비즈니스 전에 거부한다`(path: String) {
        // when
        val response = mvc.perform(request(HttpMethod.GET, path))

        // then
        response.andExpect(status().isForbidden())
        confirmVerified(travel)
    }
}

@WebMvcTest(
    properties = ["pawbridge.animal-travel.enabled=true", "pawbridge.animal-query.backend=elasticsearch"],
    excludeAutoConfiguration = [UserDetailsServiceAutoConfiguration::class],
)
@ContextConfiguration(
    classes = [PetTravelController::class, ReadSecurityConfiguration::class, F3TestInputs.SecurityInputs::class],
)
class AnimalTravelBackendDisabledHttpTest {
    @Autowired
    lateinit var mvc: MockMvc

    @MockkBean
    lateinit var travel: PetTravel

    @ParameterizedTest
    @CsvSource("/api/v1/places/regions", "/api/v1/places", "/api/v1/places/7")
    fun `조회 백엔드가 맞지 않을 때__공개 조회를 요청하면__비즈니스 전에 거부한다`(path: String) {
        // when
        val response = mvc.perform(request(HttpMethod.GET, path))

        // then
        response.andExpect(status().isForbidden())
        confirmVerified(travel)
    }
}

@WebMvcTest(
    properties = ["pawbridge.animal-travel.enabled=true", "pawbridge.animal-query.backend=postgresql"],
    excludeAutoConfiguration = [UserDetailsServiceAutoConfiguration::class],
)
@ContextConfiguration(
    classes = [PetTravelController::class, ReadSecurityConfiguration::class, F3TestInputs.SecurityInputs::class],
)
class AnimalTravelNeighborDisabledHttpTest {
    @Autowired
    lateinit var mvc: MockMvc

    @MockkBean
    lateinit var travel: PetTravel

    @ParameterizedTest
    @CsvSource("POST,/api/v1/places", "POST,/api/v1/internal/places/refresh", "DELETE,/api/v1/places/7")
    fun `공개 여행 조회만 켜졌을 때__쓰기나 수집을 요청하면__비즈니스 전에 거부한다`(
        method: String,
        path: String,
    ) {
        // when
        val response = mvc.perform(request(HttpMethod.valueOf(method), path))

        // then
        response.andExpect(status().isForbidden())
        confirmVerified(travel)
    }
}
