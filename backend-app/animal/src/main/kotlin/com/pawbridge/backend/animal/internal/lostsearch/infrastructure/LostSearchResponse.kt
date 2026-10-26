package com.pawbridge.backend.animal.internal.lostsearch.infrastructure

import com.pawbridge.backend.animal.internal.command.infrastructure.dto.response.AnimalDetailResponse

@JvmRecord
data class LostSearchResponse(val candidates: List<Candidate>) {
    // Image scores are internal ranking signals, not identity probabilities.
    @JvmRecord
    data class Candidate(
        val animal: AnimalDetailResponse,
        val shelterPhone: String?,
        val matchedEvidence: List<String?>?,
    )
}
