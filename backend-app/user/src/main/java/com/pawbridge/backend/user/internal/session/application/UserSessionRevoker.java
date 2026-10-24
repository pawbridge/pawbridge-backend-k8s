package com.pawbridge.backend.user.internal.session.application;

/** Revokes every session of a user inside the caller's current transaction. */
@FunctionalInterface
public interface UserSessionRevoker {
    UserSessionRevoker NONE = userId -> { };

    void revokeAll(long userId);
}
