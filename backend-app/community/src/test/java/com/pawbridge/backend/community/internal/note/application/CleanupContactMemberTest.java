package com.pawbridge.backend.community.internal.note.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.pawbridge.backend.community.api.ContactMemberCleanup;
import com.pawbridge.backend.user.api.ContactMember;
import com.pawbridge.backend.user.api.ContactMemberQuery;
import com.pawbridge.backend.user.api.ContactMemberUnavailableException;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class CleanupContactMemberTest {
    @ParameterizedTest
    @ValueSource(longs = {0, -1})
    void givenNonPositiveMember__whenRemoving__thenRejectLoginBeforeStoreOrUser(long memberId) {
        var calls = new ArrayList<String>();
        ContactMemberCleanup cleanup = new CleanupContactMember(id -> {
            calls.add("user");
            return new ContactMember(id, "회원", false, true);
        }, (id, guard) -> calls.add("store"));

        var failure = assertThrows(ContactMemberCleanupRejectedException.class, () -> cleanup.remove(memberId));

        assertEquals(ContactMemberCleanupRejectedException.Reason.LOGIN, failure.reason());
        assertEquals(List.of(), calls);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void givenActiveOrMissingMember__whenLockedPendingPolicyRuns__thenRejectNotPending(boolean active) {
        var calls = new ArrayList<String>();
        ContactMemberQuery members = id -> {
            calls.add("user:" + id);
            return new ContactMember(id, active ? "회원" : "탈퇴한 회원", active, false);
        };
        var cleanup = new CleanupContactMember(members, (id, guard) -> {
            calls.add("locked:" + id);
            guard.run();
            calls.add("cleanup");
        });

        var failure = assertThrows(ContactMemberCleanupRejectedException.class, () -> cleanup.remove(7));

        assertEquals(ContactMemberCleanupRejectedException.Reason.NOT_PENDING, failure.reason());
        assertEquals(List.of("locked:7", "user:7"), calls);
    }

    @Test
    void givenDeletionPendingMember__whenRemoving__thenCheckOriginalProjectionInsideStoreGuardOnce() {
        var calls = new ArrayList<String>();
        ContactMemberCleanup cleanup = new CleanupContactMember(id -> {
            calls.add("user:" + id);
            return new ContactMember(id, "탈퇴한 회원", false, true);
        }, (id, guard) -> {
            calls.add("locked:" + id);
            guard.run();
            calls.add("cleanup");
        });

        cleanup.remove(7);

        assertEquals(List.of("locked:7", "user:7", "cleanup"), calls);
    }

    @Test
    void givenUserUnavailable__whenPendingPolicyRuns__thenPropagateTypedFailureAndStopCleanup() {
        var failure = new ContactMemberUnavailableException(new IllegalStateException("private User failure"));
        var calls = new ArrayList<String>();
        var cleanup = new CleanupContactMember(id -> {
            calls.add("user");
            throw failure;
        }, (id, guard) -> {
            calls.add("locked");
            guard.run();
            calls.add("cleanup");
        });

        assertSame(failure, assertThrows(ContactMemberUnavailableException.class, () -> cleanup.remove(7)));
        assertEquals(List.of("locked", "user"), calls);
    }
}
