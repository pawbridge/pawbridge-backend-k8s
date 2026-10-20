package com.pawbridge.backend.community.api;

/** Removes Community data only after User has committed the member's deletion-pending state. */
public interface ContactMemberCleanup {
    void remove(long memberId);
}
