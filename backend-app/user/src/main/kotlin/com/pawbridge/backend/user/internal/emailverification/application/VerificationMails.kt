package com.pawbridge.backend.user.internal.emailverification.application

interface VerificationMails {
    fun sendVerification(to: String?, code: String)

    fun sendPasswordReset(to: String?, code: String)
}
