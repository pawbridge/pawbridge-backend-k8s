package com.pawbridge.backend.store.api;

/** Keeps JDBC/transaction technology out of the caller's compile-time contract. */
public final class StoreMemberReadUnavailableException extends RuntimeException {
    public StoreMemberReadUnavailableException(Throwable cause) {
        super("Store member information could not be read",cause);
    }
}
