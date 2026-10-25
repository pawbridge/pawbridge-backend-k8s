@file:Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")

package com.pawbridge.backend.animal.internal.recommendation.web

import com.pawbridge.backend.animal.internal.recommendation.application.AnimalRecommendations
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RestController

@RestController
class AnimalRecommendationController(
    private val recommendations: AnimalRecommendations,
) {

    // The path variable is java.lang.Long deliberately: kotlin.Long? makes it optional (a blank segment reaches the
    // service as null) and kotlin.Long is a primitive (a blank segment becomes a type mismatch). A non-null
    // java.lang.Long keeps the Java behaviour: required and boxed, so a blank segment fails before the handler runs.
    @GetMapping("/api/v1/animals/{id}/similar")
    fun recommend(@PathVariable("id") id: java.lang.Long): List<AnimalRecommendationResponse> {
        return recommendations.recommend(id.toLong()).map { card -> AnimalRecommendationResponse.from(card) }
    }
}
