package com.pawbridge.backend.user.api;

/** User-owned maintenance operations; callers decide when to invoke them. */
public interface UserEventRetention {
    void cleanupOldOutboxEvents();
    void cleanupOldProcessedEvents();
}
