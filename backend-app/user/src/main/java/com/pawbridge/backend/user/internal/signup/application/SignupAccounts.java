package com.pawbridge.backend.user.internal.signup.application;

public interface SignupAccounts {
    boolean existsLocalEmail(String email);

    SignedUp createLocalUser(String email, String name, String encodedPassword, SignupRole role, String careRegNo);
}
