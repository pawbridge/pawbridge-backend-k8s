package com.pawbridge.backend.animal.internal.lostsearch.infrastructure;

import java.util.List;

public record PythonLostSearchResponse(List<Candidate> candidates) {
    public record Candidate(Long animalId, Double imageScore, List<String> matchedEvidence) {}
}
