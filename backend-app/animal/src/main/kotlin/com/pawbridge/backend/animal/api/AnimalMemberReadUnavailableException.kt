package com.pawbridge.backend.animal.api

/** Read failure crosses module boundaries without exposing JDBC types in the public signature. */
class AnimalMemberReadUnavailableException(
    cause: Throwable?,
) : RuntimeException("Animal member information could not be read", cause)
