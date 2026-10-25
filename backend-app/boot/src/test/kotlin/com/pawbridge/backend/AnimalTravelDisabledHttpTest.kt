package com.pawbridge.backend

import com.pawbridge.backend.animal.internal.travel.application.PetTravel
import com.pawbridge.backend.animal.internal.travel.web.PetTravelController
import com.pawbridge.backend.boot.configuration.ReadSecurityConfiguration
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.mockito.Mockito.verifyNoInteractions
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.http.HttpMethod
import org.springframework.test.context.ContextConfiguration
import org.springframework.test.context.bean.override.mockito.MockitoBean
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

    @MockitoBean
    lateinit var travel: PetTravel

    @ParameterizedTest
    @CsvSource("/api/v1/places/regions", "/api/v1/places", "/api/v1/places/7")
    fun given__disabled_travel__when__public_read_is_requested__then__deny_before_business(path: String) {
        mvc.perform(request(HttpMethod.GET, path)).andExpect(status().isForbidden())
        verifyNoInteractions(travel)
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

    @MockitoBean
    lateinit var travel: PetTravel

    @ParameterizedTest
    @CsvSource("/api/v1/places/regions", "/api/v1/places", "/api/v1/places/7")
    fun given__wrong_read_backend__when__public_read_is_requested__then__deny_before_business(path: String) {
        mvc.perform(request(HttpMethod.GET, path)).andExpect(status().isForbidden())
        verifyNoInteractions(travel)
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

    @MockitoBean
    lateinit var travel: PetTravel

    @ParameterizedTest
    @CsvSource("POST,/api/v1/places", "POST,/api/v1/internal/places/refresh", "DELETE,/api/v1/places/7")
    fun given__only_public_travel_reading_enabled__when__write_or_collection_is_requested__then__deny_before_business(
        method: String,
        path: String,
    ) {
        mvc.perform(request(HttpMethod.valueOf(method), path)).andExpect(status().isForbidden())
        verifyNoInteractions(travel)
    }
}
