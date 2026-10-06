package com.pawbridge.communityservice.contact;

import static com.pawbridge.communityservice.contact.PrivateNoteModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.time.*;
import java.util.Optional;
import java.util.List;
import java.util.UUID;
import com.pawbridge.communityservice.client.UserServiceClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.web.server.ResponseStatusException;

class PrivateNoteServiceTest {
    PrivateNoteRepository repository=mock(PrivateNoteRepository.class);
    UserServiceClient users=mock(UserServiceClient.class);
    PrivateNoteStream stream=mock(PrivateNoteStream.class);
    Instant now=Instant.parse("2026-10-06T05:00:00Z");
    PrivateNoteService service;
    @BeforeEach void setUp() {
        service=new PrivateNoteService(repository,users,stream,Clock.fixed(now,ZoneOffset.UTC),new TestTransactions());
        when(users.getContactMember(1L)).thenReturn(new ContactMember(1L,"보낸이",true,false));
        when(users.getContactMember(2L)).thenReturn(new ContactMember(2L,"받는이",true,false));
        when(repository.request(anyLong(),any())).thenReturn(Optional.empty());
    }
    private SendNote input() {return new SendNote(2L,"안녕하세요",UUID.randomUUID(),null,null,null);}
    @Test void givenValidNote_whenSend_thenStoreAndEmitOnlyAfterCommit() {
        var input=input();
        Receipt receipt=service.send(1,input);
        verify(repository).insert(eq(1L),eq(input),eq("안녕하세요"),eq(receipt.noteId()),anyString(),eq(now),eq(now.atZone(ZoneOffset.UTC).plusYears(1).toInstant()));
        verify(stream).publish(eq(2L),argThat(event -> event.noteId().equals(receipt.noteId()) && event.actorNickname().equals("보낸이")));
    }
    @Test void givenFailedWrite_whenSend_thenDoNotPublishNotification() {
        doThrow(new IllegalStateException("synthetic failure")).when(repository).insert(anyLong(),any(),anyString(),any(),anyString(),any(),any());
        assertThatThrownBy(() -> service.send(1,input())).isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(stream);
    }
    @Test void givenCommittedNote_whenPushFails_thenStillReturnSuccessfulReceipt() {
        doThrow(new IllegalStateException("synthetic push failure")).when(stream).publish(anyLong(),any());
        assertThat(service.send(1,input()).noteId()).isNotNull();
        verify(repository).insert(anyLong(),any(),anyString(),any(),anyString(),any(),any());
    }
    @Test void givenInvalidOrSelfAddressedNote_whenSend_thenDoNotTouchStorage() {
        assertThatThrownBy(() -> service.send(1,new SendNote(1L,"자신",UUID.randomUUID(),null,null,null))).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> service.send(1,new SendNote(2L," ",UUID.randomUUID(),null,null,null))).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> service.send(1,new SendNote(2L,"가".repeat(5001),UUID.randomUUID(),null,null,null))).isInstanceOf(ResponseStatusException.class);
        verifyNoInteractions(repository,users,stream);
    }
    @Test void givenFiveThousandUnicodeCharacters_whenSend_thenAcceptFullCodePoints() {
        var input=new SendNote(2L,"🐶".repeat(5000),UUID.randomUUID(),null,null,null);
        assertThat(service.send(1,input).noteId()).isNotNull();
    }
    @Test void givenBlockedOrPendingRecipient_whenSend_thenDoNotPersist() {
        when(repository.blocked(1,2)).thenReturn(true);
        assertThatThrownBy(() -> service.send(1,input())).isInstanceOf(ResponseStatusException.class);
        when(repository.blocked(1,2)).thenReturn(false);
        when(users.getContactMember(2L)).thenReturn(new ContactMember(2L,"탈퇴한 회원",false,true));
        assertThatThrownBy(() -> service.send(1,input())).isInstanceOf(ResponseStatusException.class);
        verify(repository,never()).insert(anyLong(),any(),anyString(),any(),anyString(),any(),any());
    }
    @Test void givenTenRecentNotes_whenSend_thenRateLimitWithoutPublishing() {
        when(repository.rate(1,now.minusSeconds(60))).thenReturn(10L);
        assertThatThrownBy(() -> service.send(1,input())).isInstanceOf(ResponseStatusException.class).satisfies(e -> assertThat(((ResponseStatusException)e).getStatusCode().value()).isEqualTo(429));
        verifyNoInteractions(stream);
    }
    @Test void givenOtherOwnersNote_whenReadOrDelete_thenNotFound() {
        when(repository.find(anyLong(),any(),any())).thenReturn(Optional.empty());
        UUID id=UUID.randomUUID();
        assertThatThrownBy(() -> service.get(1,id)).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> service.delete(1,id)).isInstanceOf(ResponseStatusException.class);
        verify(repository,never()).delete(anyLong(),any());
    }
    @Test void givenActiveMember_whenInternalPurgeRequested_thenReject() {
        assertThatThrownBy(() -> service.withdraw(1)).isInstanceOf(ResponseStatusException.class);
        verify(repository,never()).withdraw(anyLong());
    }
    @Test void givenPendingMember_whenPurge_thenEraseAndCloseAfterCommit() {
        when(users.getContactMember(1L)).thenReturn(new ContactMember(1L,"탈퇴한 회원",false,true));
        service.withdraw(1);
        verify(repository).withdraw(1);verify(stream).close(1);verify(stream).resyncAll();
    }

    @Test void givenTwentyNotesFromOneSender_whenNotificationsQueried_thenUseOneBatchLookup() {
        List<Note> notes = java.util.stream.IntStream.range(0, 21)
                .mapToObj(index -> retainedNote(2L)).toList();
        when(repository.notifications(1, null, now)).thenReturn(notes);
        when(users.getContactMembers(List.of(2L)))
                .thenReturn(List.of(new ContactMember(2L, "받는이", true, false)));

        Notifications result = service.notifications(1, null);

        assertThat(result.content()).hasSize(20);
        assertThat(result.nextCursor()).isEqualTo(notes.get(19).noteId());
        assertThat(result.content()).allSatisfy(note -> assertThat(note.actorNickname()).isEqualTo("받는이"));
        verify(users).getContactMember(1L);
        verify(users).getContactMembers(List.of(2L));
        verify(users, never()).getContactMember(2L);
    }

    @Test void givenTwentyDifferentSenders_whenNotificationsQueried_thenOneBatchNotTwentyHttpCalls() {
        List<Long> senderIds = java.util.stream.LongStream.rangeClosed(10, 29).boxed().toList();
        List<Note> notes = senderIds.stream().map(this::retainedNote).toList();
        when(repository.notifications(1, null, now)).thenReturn(notes);
        when(users.getContactMembers(senderIds)).thenReturn(senderIds.stream()
                .map(userId -> new ContactMember(userId, "회원 " + userId, true, false)).toList());

        assertThat(service.notifications(1, null).content()).hasSize(20);

        verify(users).getContactMember(1L);
        verify(users).getContactMembers(senderIds);
        verifyNoMoreInteractions(users);
    }

    @Test void givenMailboxOrBlockList_whenQueried_thenResolveCounterpartsInOneBatch() {
        when(repository.list(1, "INBOX", false, 0, now)).thenReturn(List.of(retainedNote(2L), retainedNote(2L)));
        when(repository.blocks(1, 0)).thenReturn(List.of(new PrivateNoteRepository.Block(2, now)));
        when(users.getContactMembers(List.of(2L)))
                .thenReturn(List.of(new ContactMember(2L, "받는이", true, false)));

        assertThat(service.list(1, "INBOX", false, 0).content()).hasSize(2);
        assertThat(service.blocks(1, 0).content()).extracting(BlockView::nickname).containsExactly("받는이");

        verify(users, times(2)).getContactMembers(List.of(2L));
        verify(users, never()).getContactMember(2L);
    }

    @Test void givenWithdrawnSenderWithoutMemberId_whenNotificationsQueried_thenSkipBatchLookup() {
        when(repository.notifications(1, null, now)).thenReturn(List.of(retainedNote(null)));

        assertThat(service.notifications(1, null).content()).singleElement().satisfies(note -> {
            assertThat(note.actorId()).isNull();
            assertThat(note.actorNickname()).isEqualTo("탈퇴한 회원");
        });

        verify(users, never()).getContactMembers(any());
    }

    @Test void givenIncompleteBatchResponse_whenNotificationsQueried_thenFailClosed() {
        when(repository.notifications(1, null, now)).thenReturn(List.of(retainedNote(2L)));
        when(users.getContactMembers(List.of(2L))).thenReturn(List.of());

        assertThatThrownBy(() -> service.notifications(1, null)).isInstanceOf(ResponseStatusException.class)
                .satisfies(failure -> assertThat(((ResponseStatusException) failure).getStatusCode().value()).isEqualTo(503));
    }

    @Test void givenForeignOrDuplicateBatchMember_whenNotificationsQueried_thenDoNotReturnUnrequestedIdentity() {
        when(repository.notifications(1, null, now)).thenReturn(List.of(retainedNote(2L), retainedNote(3L)));
        when(users.getContactMembers(List.of(2L, 3L)))
                .thenReturn(List.of(new ContactMember(2L, "정상", true, false), new ContactMember(2L, "중복", true, false)))
                .thenReturn(List.of(new ContactMember(2L, "정상", true, false), new ContactMember(999L, "다른 회원", true, false)));

        for (int attempt = 0; attempt < 2; attempt++) {
            assertThatThrownBy(() -> service.notifications(1, null)).isInstanceOf(ResponseStatusException.class)
                    .satisfies(failure -> assertThat(((ResponseStatusException) failure).getStatusCode().value()).isEqualTo(503));
        }
    }

    @Test void givenPartialContext_whenSend_thenBadRequestBeforeStorage() {
        assertThatThrownBy(() -> service.send(1, new SendNote(2L, "합성 본문", UUID.randomUUID(), null, "POST", null)))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(failure -> assertThat(((ResponseStatusException) failure).getStatusCode().value()).isEqualTo(400));
        verifyNoInteractions(repository, users, stream);
    }

    private Note retainedNote(Long senderId) {
        return new Note(UUID.randomUUID(), senderId, 1L, "비공개 합성 본문", null, null, null,
                now, now.plusSeconds(3600), "INBOX", null, false);
    }
    private static class TestTransactions extends AbstractPlatformTransactionManager {
        @Override protected Object doGetTransaction() {return new Object();}
        @Override protected void doBegin(Object tx,TransactionDefinition definition) {}
        @Override protected void doCommit(DefaultTransactionStatus status) {}
        @Override protected void doRollback(DefaultTransactionStatus status) {}
    }
}
