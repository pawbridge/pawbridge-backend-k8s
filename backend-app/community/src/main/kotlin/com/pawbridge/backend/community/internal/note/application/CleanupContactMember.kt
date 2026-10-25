package com.pawbridge.backend.community.internal.note.application

import com.pawbridge.backend.community.api.ContactMemberCleanup
import com.pawbridge.backend.user.api.ContactMemberQuery

class CleanupContactMember(
    private val members: ContactMemberQuery,
    private val store: ContactMemberCleanupStore,
) : ContactMemberCleanup {

    override fun remove(memberId: Long) {
        if (memberId <= 0) {
            throw ContactMemberCleanupRejectedException(ContactMemberCleanupRejectedException.Reason.LOGIN)
        }
        store.remove(
            memberId,
            Runnable {
                val member = members.get(memberId)
                if (member.active || !member.deletionPending) {
                    throw ContactMemberCleanupRejectedException(ContactMemberCleanupRejectedException.Reason.NOT_PENDING)
                }
            },
        )
    }
}
