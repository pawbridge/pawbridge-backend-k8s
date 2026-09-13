package com.pawbridge.animalservice.lostsearch;

import java.util.List;

public record PythonLostSearchResponse(List<Candidate> candidates) {
    public record Candidate(Long animalId, Double imageScore, List<String> matchedEvidence) {}
}
