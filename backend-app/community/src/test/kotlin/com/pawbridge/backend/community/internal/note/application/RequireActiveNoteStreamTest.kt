package com.pawbridge.backend.community.internal.note.application

import com.pawbridge.backend.user.api.ContactMember
import com.pawbridge.backend.user.api.ContactMemberQuery
import com.pawbridge.backend.user.api.ContactMemberUnavailableException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.verifyNoMoreInteractions
import org.mockito.Mockito.`when` as whenever

class RequireActiveNoteStreamTest {
    @ParameterizedTest
    @ValueSource(longs = [0, -1])
    fun givenNonpositiveCaller__whenCheckingStreamAccess__thenRejectBeforeMemberQuery(id: Long) {
        val members = mock(ContactMemberQuery::class.java)

        assertThatThrownBy { RequireActiveNoteStream(members).requireActive(id) }
            .isInstanceOfSatisfying(NoteStreamRejectedException::class.java) { failure ->
                assertThat(failure.reason()).isEqualTo(NoteStreamRejectedException.Reason.LOGIN)
            }
        verifyNoInteractions(members)
    }

    @ParameterizedTest
    @ValueSource(booleans = [true, false])
    fun givenCurrentMemberState__whenCheckingStreamAccess__thenQueryOnlyCallerAndPreserveActivePolicy(active: Boolean) {
        val members = mock(ContactMemberQuery::class.java)
        whenever(members.get(7)).thenReturn(ContactMember(7L, " 原文 ", active, !active))
        val guard = RequireActiveNoteStream(members)
        if (active) {
            guard.requireActive(7)
        } else {
            assertThatThrownBy { guard.requireActive(7) }
                .isInstanceOfSatisfying(NoteStreamRejectedException::class.java) { failure ->
                    assertThat(failure.reason()).isEqualTo(NoteStreamRejectedException.Reason.INACTIVE)
                }
        }
        verify(members).get(7)
        verifyNoMoreInteractions(members)
    }

    @Test
    fun givenMemberUnavailable__whenCheckingStreamAccess__thenPropagateTypedFailureWithoutFallback() {
        val members = mock(ContactMemberQuery::class.java)
        val failure = ContactMemberUnavailableException(IllegalStateException("synthetic-private"))
        whenever(members.get(7)).thenThrow(failure)

        assertThatThrownBy { RequireActiveNoteStream(members).requireActive(7) }.isSameAs(failure)
        verify(members).get(7)
        verifyNoMoreInteractions(members)
    }
}
