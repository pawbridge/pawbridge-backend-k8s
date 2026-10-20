package com.pawbridge.backend.animal.api;

/** Animal-owned lookup for callers that already checked their User/member/admin authorization. */
public interface AnimalShelterReads {
    record Shelter(Long id, String careRegNo, String name) { }

    /**
     * Retains the existing registration lookup's fields and failure classification.
     * Source missing registration is a server failure, not a confirmed404/Optional.empty.
     * A null result preserves an empty remote body; the caller decides its existing unavailable policy.
     */
    Shelter findByCareRegNo(String careRegNo);
}
