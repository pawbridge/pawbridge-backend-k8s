package com.pawbridge.backend.user.internal.animalactivity.application

/** The caller is not an approved shelter member, so it has no shelter whose registered animals could be listed. */
class ShelterMembershipRequiredException : RuntimeException("Shelter membership is required")
