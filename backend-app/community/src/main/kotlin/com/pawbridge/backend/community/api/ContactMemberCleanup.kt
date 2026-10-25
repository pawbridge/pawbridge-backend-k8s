package com.pawbridge.backend.community.api

/** Removes Community data only after User has committed the member's deletion-pending state. */
interface ContactMemberCleanup {
    fun remove(memberId: Long)
}
