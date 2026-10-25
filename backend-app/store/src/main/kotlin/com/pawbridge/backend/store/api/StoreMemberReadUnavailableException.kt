package com.pawbridge.backend.store.api

/** Keeps JDBC/transaction technology out of the caller's compile-time contract. */
class StoreMemberReadUnavailableException(
    cause: Throwable?,
) : RuntimeException("Store member information could not be read", cause)
