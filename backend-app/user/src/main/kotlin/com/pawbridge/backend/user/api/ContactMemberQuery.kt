package com.pawbridge.backend.user.api

interface ContactMemberQuery {
    /** Returns a non-null projection; an absent user is inactive with the requested ID. */
    fun get(userId: Long): ContactMember
}
