package com.pawbridge.backend.animal.internal.recommendation.web;

import com.pawbridge.backend.animal.internal.recommendation.application.AnimalRecommendations;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
public final class AnimalRecommendationController {
    private final AnimalRecommendations recommendations;
    public AnimalRecommendationController(AnimalRecommendations recommendations) {
        this.recommendations = recommendations;
    }

    @GetMapping("/api/v1/animals/{id}/similar")
    public List<AnimalRecommendationResponse> recommend(@PathVariable("id") Long id) {
        return recommendations.recommend(id).stream().map(AnimalRecommendationResponse::from).toList();
    }
}
