package com.pawbridge.backend.animal.internal.lostsearch.infrastructure

@JvmRecord
data class PythonLostSearchResponse(val candidates: List<Candidate?>?) {
    @JvmRecord
    data class Candidate(
        val animalId: Long?,
        val imageScore: Double?,
        val matchedEvidence: List<String?>?,
    )
}
