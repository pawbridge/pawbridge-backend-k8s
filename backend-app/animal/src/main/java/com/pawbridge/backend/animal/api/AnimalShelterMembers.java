package com.pawbridge.backend.animal.api;

/** User observes current careRegNo membership in the caller's shared writer transaction. */
@FunctionalInterface
public interface AnimalShelterMembers {
    boolean exists(String careRegNo);
}
