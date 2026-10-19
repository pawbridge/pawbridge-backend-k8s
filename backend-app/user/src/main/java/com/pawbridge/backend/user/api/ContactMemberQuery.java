package com.pawbridge.backend.user.api;

public interface ContactMemberQuery {
    /** Returns a non-null projection; an absent user is inactive with the requested ID. */
    ContactMember get(long userId);
}
