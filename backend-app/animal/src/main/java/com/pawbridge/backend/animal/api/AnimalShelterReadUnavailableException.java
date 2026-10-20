package com.pawbridge.backend.animal.api;

/** Keeps Animal persistence/transaction types out of the caller's compile-time contract. */
public final class AnimalShelterReadUnavailableException extends RuntimeException {
    public AnimalShelterReadUnavailableException(Throwable cause) {
        super("Animal shelter information could not be read", cause);
    }
}
