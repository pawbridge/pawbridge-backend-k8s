package com.pawbridge.backend.animal.api;

/** Animal-owned favorite counter. Runs in the caller's transaction so the count commits with the favorite row. */
public interface AnimalFavoriteCounts {
    /**
     * Applies {@code +1} or {@code -1} to the animal's favorite count and refreshes its update time.
     * A decrement never takes the count below zero. Returns false when the animal does not exist.
     */
    boolean adjust(long animalId, int delta);
}
