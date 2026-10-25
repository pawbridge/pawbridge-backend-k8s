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
    fun `0 이하의 회원 ID가 주어졌을 때__삭제하면__저장소와 회원 조회 전에 로그인 거절로 끝난다`(memberId: Long) {
        // given
        val calls = ArrayList<String>()
        val cleanup: ContactMemberCleanup = CleanupContactMember(
            contactMemberQuery { id ->
                calls.add("user")
                ContactMember(id, "회원", false, true)
            },
            contactMemberCleanupStore { _, _ -> calls.add("store") },
        )

        // when
        val failure = assertThrows(ContactMemberCleanupRejectedException::class.java) { cleanup.remove(memberId) }

        // then
        assertEquals(ContactMemberCleanupRejectedException.Reason.LOGIN, failure.reason())
        assertEquals(listOf<String>(), calls)
    }

    @ParameterizedTest
    @ValueSource(booleans = [true, false])
    fun `활성이거나 이미 없는 회원이 주어졌을 때__잠금 안에서 탈퇴 대기 정책이 실행되면__탈퇴 대기 아님으로 거절한다`(active: Boolean) {
        // given
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

        // when
        val failure = assertThrows(ContactMemberCleanupRejectedException::class.java) { cleanup.remove(7) }

        // then
        assertEquals(ContactMemberCleanupRejectedException.Reason.NOT_PENDING, failure.reason())
        assertEquals(listOf("locked:7", "user:7"), calls)
    }

    @Test
    fun `탈퇴 대기 회원이 주어졌을 때__삭제하면__저장소 가드 안에서 원본 회원 정보를 한 번 확인하고 정리한다`() {
        // given
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

        // when
        cleanup.remove(7)

        // then
        assertEquals(listOf("locked:7", "user:7", "cleanup"), calls)
    }

    @Test
    fun `회원 조회를 쓸 수 없을 때__탈퇴 대기 정책이 실행되면__타입 있는 실패를 그대로 전파하고 정리를 멈춘다`() {
        // given
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

        // when
        val thrown = assertThrows(ContactMemberUnavailableException::class.java) { cleanup.remove(7) }

        // then
        assertSame(failure, thrown)
        assertEquals(listOf("locked", "user"), calls)
    }
}
