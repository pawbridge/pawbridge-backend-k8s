package com.pawbridge.backend.community.internal.note.application

import com.pawbridge.backend.community.api.ContactMemberCleanup
import com.pawbridge.backend.community.support.contactMemberCleanupStore
import com.pawbridge.backend.community.support.contactMemberQuery
import com.pawbridge.backend.user.api.ContactMember
import com.pawbridge.backend.user.api.ContactMemberUnavailableException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class CleanupContactMemberTest {
    @ParameterizedTest
    @ValueSource(longs = [0, -1])
    fun givenNonPositiveMember__whenRemoving__thenRejectLoginBeforeStoreOrUser(memberId: Long) {
        val calls = ArrayList<String>()
        val cleanup: ContactMemberCleanup = CleanupContactMember(
            contactMemberQuery { id ->
                calls.add("user")
                ContactMember(id, "회원", false, true)
            },
            contactMemberCleanupStore { _, _ -> calls.add("store") },
        )

        val failure = assertThrows(ContactMemberCleanupRejectedException::class.java) { cleanup.remove(memberId) }

        assertEquals(ContactMemberCleanupRejectedException.Reason.LOGIN, failure.reason())
        assertEquals(listOf<String>(), calls)
    }

    @ParameterizedTest
    @ValueSource(booleans = [true, false])
    fun givenActiveOrMissingMember__whenLockedPendingPolicyRuns__thenRejectNotPending(active: Boolean) {
        val calls = ArrayList<String>()
        val members = contactMemberQuery { id ->
            calls.add("user:$id")
            ContactMember(id, if (active) "회원" else "탈퇴한 회원", active, false)
        }
        val cleanup = CleanupContactMember(
            members,
            contactMemberCleanupStore { id, guard ->
                calls.add("locked:$id")
                guard.run()
                calls.add("cleanup")
            },
        )

        val failure = assertThrows(ContactMemberCleanupRejectedException::class.java) { cleanup.remove(7) }

        assertEquals(ContactMemberCleanupRejectedException.Reason.NOT_PENDING, failure.reason())
        assertEquals(listOf("locked:7", "user:7"), calls)
    }

    @Test
    fun givenDeletionPendingMember__whenRemoving__thenCheckOriginalProjectionInsideStoreGuardOnce() {
        val calls = ArrayList<String>()
        val cleanup: ContactMemberCleanup = CleanupContactMember(
            contactMemberQuery { id ->
                calls.add("user:$id")
                ContactMember(id, "탈퇴한 회원", false, true)
            },
            contactMemberCleanupStore { id, guard ->
                calls.add("locked:$id")
                guard.run()
                calls.add("cleanup")
            },
        )

        cleanup.remove(7)

        assertEquals(listOf("locked:7", "user:7", "cleanup"), calls)
    }

    @Test
    fun givenUserUnavailable__whenPendingPolicyRuns__thenPropagateTypedFailureAndStopCleanup() {
        val failure = ContactMemberUnavailableException(IllegalStateException("private User failure"))
        val calls = ArrayList<String>()
        val cleanup = CleanupContactMember(
            contactMemberQuery {
                calls.add("user")
                throw failure
            },
            contactMemberCleanupStore { _, guard ->
                calls.add("locked")
                guard.run()
                calls.add("cleanup")
            },
        )

        assertSame(failure, assertThrows(ContactMemberUnavailableException::class.java) { cleanup.remove(7) })
        assertEquals(listOf("locked", "user"), calls)
    }
}
