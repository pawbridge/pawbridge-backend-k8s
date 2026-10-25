package com.pawbridge.backend.animal.internal.recommendation.web

import com.pawbridge.backend.animal.internal.recommendation.application.AnimalRecommendations
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RestController

@RestController
class AnimalRecommendationController(
    private val recommendations: AnimalRecommendations,
) {

    @GetMapping("/api/v1/animals/{id}/similar")
    fun recommend(@PathVariable("id") id: Long?): List<AnimalRecommendationResponse> {
        return recommendations.recommend(id!!).map { card -> AnimalRecommendationResponse.from(card) }
    }
}
