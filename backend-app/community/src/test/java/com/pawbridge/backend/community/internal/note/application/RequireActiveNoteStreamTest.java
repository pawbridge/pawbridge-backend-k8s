package com.pawbridge.backend.community.internal.note.application;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.pawbridge.backend.user.api.ContactMember;
import com.pawbridge.backend.user.api.ContactMemberQuery;
import com.pawbridge.backend.user.api.ContactMemberUnavailableException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class RequireActiveNoteStreamTest {
    @ParameterizedTest @ValueSource(longs = {0, -1})
    void givenNonpositiveCaller__whenCheckingStreamAccess__thenRejectBeforeMemberQuery(long id) {
        var members = mock(ContactMemberQuery.class);
        assertThatThrownBy(() -> new RequireActiveNoteStream(members).requireActive(id))
                .isInstanceOfSatisfying(NoteStreamRejectedException.class,
                        failure -> assertThat(failure.reason()).isEqualTo(NoteStreamRejectedException.Reason.LOGIN));
        verifyNoInteractions(members);
    }

    @ParameterizedTest @ValueSource(booleans = {true, false})
    void givenCurrentMemberState__whenCheckingStreamAccess__thenQueryOnlyCallerAndPreserveActivePolicy(boolean active) {
        var members = mock(ContactMemberQuery.class);
        when(members.get(7)).thenReturn(new ContactMember(7L, " 原文 ", active, !active));
        var guard = new RequireActiveNoteStream(members);
        if (active) guard.requireActive(7);
        else assertThatThrownBy(() -> guard.requireActive(7)).isInstanceOfSatisfying(NoteStreamRejectedException.class,
                failure -> assertThat(failure.reason()).isEqualTo(NoteStreamRejectedException.Reason.INACTIVE));
        verify(members).get(7); verifyNoMoreInteractions(members);
    }

    @Test void givenMemberUnavailable__whenCheckingStreamAccess__thenPropagateTypedFailureWithoutFallback() {
        var members = mock(ContactMemberQuery.class);
        var failure = new ContactMemberUnavailableException(new IllegalStateException("synthetic-private"));
        when(members.get(7)).thenThrow(failure);
        assertThatThrownBy(() -> new RequireActiveNoteStream(members).requireActive(7)).isSameAs(failure);
        verify(members).get(7); verifyNoMoreInteractions(members);
    }
}
