package com.pawbridge.backend.animal.api

/** User observes current careRegNo membership in the caller's shared writer transaction. */
fun interface AnimalShelterMembers {
    fun exists(careRegNo: String?): Boolean
}
