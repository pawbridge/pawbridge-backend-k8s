package com.pawbridge.backend.user.api

interface ContactMembersQuery {
    /**
     * Accepts 1..21 positive IDs before deduplication and returns one projection per distinct ID,
     * in first-occurrence order. Missing users retain the requested ID and are inactive.
     *
     * @throws IllegalArgumentException if the raw list or an ID is invalid
     * @throws ContactMemberUnavailableException if the supplied read transaction or lookup fails
     */
    fun getAll(userIds: List<Long?>?): List<ContactMember>
}
