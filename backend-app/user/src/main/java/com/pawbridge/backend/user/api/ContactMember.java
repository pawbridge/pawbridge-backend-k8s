package com.pawbridge.backend.user.api;

/** Contact projection without private profile or provider information. */
public record ContactMember(Long userId, String nickname, boolean active, boolean deletionPending) {
}
