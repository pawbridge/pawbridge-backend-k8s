package com.pawbridge.animalservice.dto.request;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * Python AI Service 유사 동물 조회 요청 DTO
 */
@Getter
@AllArgsConstructor
public class SimilarAnimalRequest {

    @JsonProperty("animal_id")
    private Long animalId;

    @JsonProperty("image_url")
    private String imageUrl;
}
