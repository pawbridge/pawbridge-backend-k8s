package com.pawbridge.backend.user.internal.signup.application

interface SignupAccounts {
    fun existsLocalEmail(email: String?): Boolean

    fun createLocalUser(
        email: String?,
        name: String?,
        encodedPassword: String?,
        role: SignupRole,
        careRegNo: String?,
    ): SignedUp
}
