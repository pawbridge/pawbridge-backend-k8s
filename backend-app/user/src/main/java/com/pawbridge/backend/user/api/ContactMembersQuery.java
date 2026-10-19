package com.pawbridge.backend.user.api;

import java.util.List;

public interface ContactMembersQuery {
    /**
     * Accepts 1..21 positive IDs before deduplication and returns one projection per distinct ID,
     * in first-occurrence order. Missing users retain the requested ID and are inactive.
     *
     * @throws IllegalArgumentException if the raw list or an ID is invalid
     * @throws ContactMemberUnavailableException if the supplied read transaction or lookup fails
     */
    List<ContactMember> getAll(List<Long> userIds);
}
