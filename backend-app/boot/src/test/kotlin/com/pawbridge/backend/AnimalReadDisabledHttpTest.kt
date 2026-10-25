package com.pawbridge.backend

import com.ninjasquad.springmockk.MockkBean
import com.pawbridge.backend.animal.internal.discovery.application.AnimalDiscovery
import com.pawbridge.backend.animal.internal.discovery.web.AnimalDiscoveryController
import com.pawbridge.backend.animal.internal.discovery.web.AnimalDiscoveryExceptionHandler
import com.pawbridge.backend.animal.internal.recommendation.application.AnimalRecommendations
import com.pawbridge.backend.animal.internal.recommendation.web.AnimalRecommendationController
import com.pawbridge.backend.animal.internal.recommendation.web.AnimalRecommendationExceptionHandler
import com.pawbridge.backend.boot.configuration.ReadSecurityConfiguration
import io.mockk.confirmVerified
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.test.context.ContextConfiguration
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

@WebMvcTest(excludeAutoConfiguration = [UserDetailsServiceAutoConfiguration::class])
@ContextConfiguration(
    classes = [
        AnimalDiscoveryController::class,
        AnimalDiscoveryExceptionHandler::class,
        AnimalRecommendationController::class,
        AnimalRecommendationExceptionHandler::class,
        ReadSecurityConfiguration::class,
        F3TestInputs.SecurityInputs::class,
    ],
)
class AnimalReadDisabledHttpTest {
    @Autowired
    lateinit var mvc: MockMvc

    @MockkBean
    lateinit var animals: AnimalDiscovery

    @MockkBean
    lateinit var recommendations: AnimalRecommendations

    @Test
    fun `기능 플래그가 기본값일 때__관리자 토큰으로도 동물 조회를 요청하면__기본 거부를 유지한다`() {
        // when
        for (path in listOf("/api/v1/animals", "/api/v1/animals/42", "/api/v1/animals/42/similar")) {
            mvc.perform(get(path)).andExpect(status().isForbidden())
            mvc.perform(get(path).header("Authorization", "Bearer " + F3TestInputs.token(7, "ROLE_ADMIN")))
                .andExpect(status().isForbidden())
        }

        // then
        confirmVerified(animals, recommendations)
    }
}
