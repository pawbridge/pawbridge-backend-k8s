package com.pawbridge.backend.animal.api

/** Animal-owned lookup for callers that already checked their User/member/admin authorization. */
interface AnimalShelterReads {
    @JvmRecord
    data class Shelter(
        val id: Long?,
        val careRegNo: String?,
        val name: String?,
    )

    /**
     * Retains the existing registration lookup's fields and failure classification.
     * Source missing registration is a server failure, not a confirmed404/Optional.empty.
     * A null result preserves an empty remote body; the caller decides its existing unavailable policy.
     */
    fun findByCareRegNo(careRegNo: String?): Shelter?
}
