package com.pawbridge.backend.store.api;

/** Root supplies current User state without a Store-to-User implementation dependency. */
public interface StoreOrderActors {
    record Actor(long userId, boolean active, boolean deleted) { }
    /** Runs inside the active writer transaction; the implementation locks the User row FOR UPDATE. */
    Actor currentForWrite(long verifiedUserId);
    /** Runs inside the order read-only transaction; no User write lock is requested. */
    Actor currentForRead(long verifiedUserId);
}
