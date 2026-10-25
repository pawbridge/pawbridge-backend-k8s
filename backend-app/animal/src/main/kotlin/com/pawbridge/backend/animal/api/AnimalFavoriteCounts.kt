package com.pawbridge.backend.animal.api

/** Animal-owned favorite counter. Runs in the caller's transaction so the count commits with the favorite row. */
interface AnimalFavoriteCounts {
    /**
     * Applies `+1` or `-1` to the animal's favorite count and refreshes its update time.
     * A decrement never takes the count below zero. Returns false when the animal does not exist.
     */
    fun adjust(animalId: Long, delta: Int): Boolean
}
