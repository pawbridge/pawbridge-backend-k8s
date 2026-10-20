package com.pawbridge.backend.animal.api;

import java.util.List;

/** Animal-owned durable favorite delivery. Call each operation outside the caller's transaction. */
public interface AnimalFavoriteEvents {
    record FavoriteEvent(String eventId, long userId, long animalId, String eventType) { }

    /** Original immutable Outbox row; payload and Outbox event IDs have different roles. */
    record CompensationEnvelope(long outboxId, String outboxEventId, String aggregateId,
            String eventType, String topic, String payload) { }

    void applyFavorite(FavoriteEvent event);

    /**
     * After the source ADDED retry window, reobserve and claim the payload ID in a fresh transaction.
     * An uncertain apply commit is resolved by its receipt/unique claim; unreadable outcomes propagate.
     */
    void recoverAdded(FavoriteEvent event);

    /** The exclusive handoff floor is fixed across polls; it is not a moving delivery cursor. */
    List<CompensationEnvelope> pendingCompensations(long afterFloor, int limit);

    /** Call only after the User compensation transaction has successfully committed. */
    void acknowledgeCompensation(CompensationEnvelope envelope);
}
