package com.pawbridge.userservice.contact;

/** Internal projection: excludes email, real name, password and provider identity. */
public record ContactMember(Long userId, String nickname, boolean active, boolean deletionPending) {
    static ContactMember missing(long userId) {
        return new ContactMember(userId, "탈퇴한 회원", false, false);
    }
}
