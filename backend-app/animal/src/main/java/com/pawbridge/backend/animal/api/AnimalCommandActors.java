package com.pawbridge.backend.animal.api;

/** Root resolves the verified principal against the current User account; no header-derived roles. */
@FunctionalInterface
public interface AnimalCommandActors {
    Actor find(long verifiedUserId);

    record Actor(long userId, String role, String careRegNo, boolean active, boolean deleted) { }
}
