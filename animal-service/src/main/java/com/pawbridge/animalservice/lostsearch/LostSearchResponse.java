package com.pawbridge.animalservice.lostsearch;

import com.pawbridge.animalservice.dto.response.AnimalDetailResponse;
import java.util.List;

public record LostSearchResponse(List<Candidate> candidates) {
    // Image scores are internal ranking signals, not identity probabilities.
    public record Candidate(AnimalDetailResponse animal, String shelterPhone, List<String> matchedEvidence) {}
}
