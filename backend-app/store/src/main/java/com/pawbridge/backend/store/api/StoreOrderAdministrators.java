package com.pawbridge.backend.store.api;

/** Current User authority inside the caller's shared Store writer transaction. */
public interface StoreOrderAdministrators {
    record Actor(long userId, boolean active, boolean deleted, String role) { }
    Actor currentForRead(long userId);
    Actor currentForWrite(long userId);
}
