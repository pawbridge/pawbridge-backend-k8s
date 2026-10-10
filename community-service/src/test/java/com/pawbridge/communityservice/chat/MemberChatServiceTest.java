package com.pawbridge.communityservice.chat;

import static com.pawbridge.communityservice.chat.MemberChatModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.pawbridge.communityservice.client.UserServiceClient;
import com.pawbridge.communityservice.contact.PrivateNoteModels.ContactMember;
import com.pawbridge.communityservice.contact.PrivateNoteRepository;
import java.time.*;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.web.server.ResponseStatusException;

class MemberChatServiceTest {
    private final MemberChatRepository repository = mock(MemberChatRepository.class);
    private final PrivateNoteRepository contact = mock(PrivateNoteRepository.class);
    private final UserServiceClient users = mock(UserServiceClient.class);
    private final MemberChatSignals signals = mock(MemberChatSignals.class);
    private final Instant now = Instant.parse("2026-10-10T05:00:00Z");
    private MemberChatService service;
    private final UUID room = UUID.randomUUID();

    @BeforeEach void prepare() {
        service = new MemberChatService(repository, contact, users, signals,
                Clock.fixed(now, ZoneOffset.UTC), new Transactions());
        when(users.getContactMember(anyLong())).thenAnswer(call ->
                new ContactMember(call.getArgument(0), "합성 회원", true, false));
        when(repository.request(anyLong(), any(), any())).thenReturn(Optional.empty());
        when(repository.roomForSend(1, 2)).thenReturn(room);
        when(repository.insert(anyLong(), any(), any(), anyString(), any(), any()))
                .thenAnswer(call -> new Receipt(((Send)call.getArgument(1)).requestId(), room, 1));
    }

    private Send draft() { return new Send(2L, UUID.randomUUID(), "안녕하세요", null, null); }

    @Test void givenValidMessage_whenSend_thenCommitBeforeBothSignalsAndReturnApplicationReceipt() {
        Send request = draft();
        assertThat(service.send(1, request)).isEqualTo(new Receipt(request.requestId(), room, 1));
        verify(contact).lockMembers(1, 2);
        verify(repository).insert(eq(1L), eq(request), eq(room), anyString(), eq(now),
                eq(now.atZone(ZoneOffset.UTC).plusYears(1).toInstant()));
        verify(signals).publish(new Signal(1, "SENT", room, 1));
        verify(signals).publish(new Signal(2, "MESSAGE", room, 1));
    }

    @Test void givenDatabaseWriteFailure_whenSend_thenDoNotPublishOrAcknowledge() {
        doThrow(new IllegalStateException("synthetic")).when(repository)
                .insert(anyLong(), any(), any(), anyString(), any(), any());
        assertThatThrownBy(() -> service.send(1, draft())).isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(signals);
    }

    @Test void givenSameRequest_whenRetry_thenReturnPriorReceiptWithoutCountingOrPublishingAgain() {
        Send request = draft();
        Receipt prior = new Receipt(request.requestId(), room, 11);
        when(repository.request(1, request.requestId(), now)).thenReturn(Optional.of(
                new MemberChatRepository.SavedRequest(MemberChatService.hash(request), prior, true)));
        assertThat(service.send(1, request)).isEqualTo(prior);
        verify(repository, never()).rate(anyLong(), any());
        verify(repository, never()).insert(anyLong(), any(), any(), anyString(), any(), any());
        verifyNoInteractions(signals);
    }

    @Test void givenSameRequestWithDifferentBody_whenSend_thenConflict() {
        Send request = draft();
        when(repository.request(1, request.requestId(), now)).thenReturn(Optional.of(
                new MemberChatRepository.SavedRequest("different", new Receipt(request.requestId(), room, 1), true)));
        assertThatThrownBy(() -> service.send(1, request)).isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> assertThat(((ResponseStatusException)error).getStatusCode().value()).isEqualTo(409));
        verifyNoInteractions(signals);
    }

    @Test void givenBlockedPairOrRateLimit_whenSend_thenDoNotCreateRoom() {
        when(contact.blocked(1, 2)).thenReturn(true);
        assertThatThrownBy(() -> service.send(1, draft())).isInstanceOf(ResponseStatusException.class);
        when(contact.blocked(1, 2)).thenReturn(false);
        when(repository.rate(1, now.minusSeconds(60))).thenReturn(60L);
        assertThatThrownBy(() -> service.send(1, draft())).isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> assertThat(((ResponseStatusException)error).getStatusCode().value()).isEqualTo(429));
        verify(repository, never()).roomForSend(anyLong(), anyLong());
    }

    @Test void givenUnicodeMessage_whenValidate_thenCountCodePointsNotUtf16Units() {
        String emoji = new String(Character.toChars(0x1F408));
        assertThat(MemberChatService.validate(1, new Send(2L, UUID.randomUUID(), emoji.repeat(2000), null, null)).body())
                .isEqualTo(emoji.repeat(2000));
        assertThatThrownBy(() -> MemberChatService.validate(1,
                new Send(2L, UUID.randomUUID(), emoji.repeat(2001), null, null))).isInstanceOf(ResponseStatusException.class);
    }

    @Test void givenInvalidRecipientBodyOrContext_whenSend_thenDoNotTouchStorage() {
        for (Send request : java.util.List.of(
                new Send(1L, UUID.randomUUID(), "자신", null, null),
                new Send(2L, UUID.randomUUID(), " ", null, null),
                new Send(2L, UUID.randomUUID(), "본문", "POST", null),
                new Send(2L, UUID.randomUUID(), "\uD800", null, null))) {
            assertThatThrownBy(() -> service.send(1, request)).isInstanceOf(ResponseStatusException.class);
        }
        verifyNoInteractions(repository, contact, users, signals);
    }

    private static class Transactions extends AbstractPlatformTransactionManager {
        @Override protected Object doGetTransaction() { return new Object(); }
        @Override protected void doBegin(Object transaction, TransactionDefinition definition) {}
        @Override protected void doCommit(DefaultTransactionStatus status) {}
        @Override protected void doRollback(DefaultTransactionStatus status) {}
    }
}
