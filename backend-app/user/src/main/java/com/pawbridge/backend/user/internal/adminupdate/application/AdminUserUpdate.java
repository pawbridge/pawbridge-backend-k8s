package com.pawbridge.backend.user.internal.adminupdate.application;

public interface AdminUserUpdate {
    void update(long userId, Changes changes);

    record Changes(String nickname, Role role, String careRegNo) { }

    enum Role { ROLE_USER, ROLE_ADMIN, ROLE_SHELTER }
}
