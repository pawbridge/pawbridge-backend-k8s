package com.pawbridge.backend.animal.api;

/** Read failure crosses module boundaries without exposing JDBC types in the public signature. */
public final class AnimalMemberReadUnavailableException extends RuntimeException {
    public AnimalMemberReadUnavailableException(Throwable cause) {
        super("Animal member information could not be read", cause);
    }
}
