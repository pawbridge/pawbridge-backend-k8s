package com.pawbridge.communityservice.contact;

import static com.pawbridge.communityservice.contact.PrivateNoteModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.pawbridge.communityservice.client.UserServiceClient;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

@Tag("postgresql")
@EnabledIfEnvironmentVariable(named="PRIVATE_NOTES_PG_TEST_PORT", matches="[0-9]{1,5}")
class PrivateNotePostgresqlTest {
    static JdbcTemplate jdbc;
    static DataSourceTransactionManager manager;
    PrivateNoteRepository repository;
    UserServiceClient users;
    PrivateNoteStream stream;
    PrivateNoteService service;
    static final Instant NOW = Instant.parse("2026-10-06T14:59:50Z");

    @BeforeAll static void guardedDatabase() {
        String url="jdbc:postgresql://127.0.0.1:"+System.getenv("PRIVATE_NOTES_PG_TEST_PORT")+"/pawbridge";
        var source=new DriverManagerDataSource(url,"postgres","local_pg_test_only");
        jdbc=new JdbcTemplate(source);
        assertThat(jdbc.queryForList("SELECT marker FROM migration_test_guard.guard",String.class))
                .containsExactly("services-pg-disposable");
        jdbc.execute("CREATE SCHEMA IF NOT EXISTS pawbridge_community");
        Flyway.configure().dataSource(source).defaultSchema("pawbridge_community")
                .locations("classpath:db/postgresql").cleanDisabled(true).loggers(new String[0]).load().migrate();
        manager=new DataSourceTransactionManager(source);
    }
    @BeforeEach void prepare() {
        // The explicit disposable marker above is required before removing test-owned mailbox rows.
        jdbc.execute("DELETE FROM pawbridge_community.private_note_mailboxes");
        jdbc.execute("DELETE FROM pawbridge_community.private_note_requests");
        jdbc.execute("DELETE FROM pawbridge_community.private_notes");
        jdbc.execute("DELETE FROM pawbridge_community.private_note_blocks");
        jdbc.execute("DELETE FROM pawbridge_community.private_note_members");
        repository=new PrivateNoteRepository(jdbc);
        users=mock(UserServiceClient.class);stream=mock(PrivateNoteStream.class);
        when(users.getContactMember(anyLong())).thenAnswer(call -> new ContactMember(call.getArgument(0),"테스트 회원",true,false));
        when(users.getContactMembers(anyList())).thenAnswer(call -> {
            List<Long> ids = call.getArgument(0);
            return ids.stream().map(id -> new ContactMember(id, "테스트 회원", true, false)).toList();
        });
        service=new PrivateNoteService(repository,users,stream,Clock.fixed(NOW,ZoneOffset.UTC),manager,
                mock(org.springframework.context.ApplicationEventPublisher.class));
    }
    SendNote draft(long recipient) { return new SendNote(recipient,"합성 테스트 본문",UUID.randomUUID(),null,null,null); }
    long rows(String table) {return jdbc.queryForObject("SELECT count(*) FROM pawbridge_community."+table,Long.class);}

    @Test void givenMigratedPrivateNoteSchema_whenForeignKeyCleanupIndexesChecked_thenReplyAndBlockedMemberIndexed() {
        assertThat(jdbc.queryForList("SELECT indexname FROM pg_indexes WHERE schemaname='pawbridge_community'", String.class))
                .contains("idx_private_note_reply_to", "idx_private_note_blocked_member");
    }

    @Test void givenConcurrentSameSend_whenRetried_thenOneOriginalAndOnePairOfMailboxes() throws Exception {
        SendNote draft=draft(2);var gate=new CountDownLatch(1);var pool=Executors.newFixedThreadPool(2);
        try {
            Callable<Receipt> action=() -> {gate.await();return service.send(1,draft);};
            var first=pool.submit(action);var second=pool.submit(action);gate.countDown();
            assertThat(first.get(15,TimeUnit.SECONDS)).isEqualTo(second.get(15,TimeUnit.SECONDS));
            assertThat(rows("private_notes")).isEqualTo(1);assertThat(rows("private_note_mailboxes")).isEqualTo(2);
            verify(stream,times(1)).publish(eq(2L),any());
        } finally {pool.shutdownNow();}
    }
    @Test void givenReusedKeyWithChangedPayload_whenSend_thenConflict() {
        SendNote first=draft(2);service.send(1,first);
        var changed=new SendNote(2L,"다른 본문",first.requestId(),null,null,null);
        assertThatThrownBy(() -> service.send(1,changed)).isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException)e).getStatusCode().value()).isEqualTo(409));
        assertThat(rows("private_notes")).isEqualTo(1);
    }
    @Test void givenRetainedInbox_whenGetReadFavoriteAndDelete_thenOwnershipAndIndependentState() {
        UUID id=service.send(1,draft(2)).noteId();
        assertThat(service.get(2,id).readAt()).isNull();assertThat(service.notifications(2,null).unreadCount()).isEqualTo(1);
        assertThatThrownBy(() -> service.get(3,id)).isInstanceOf(ResponseStatusException.class);
        service.read(2,id);service.favorite(2,id,true);
        assertThat(service.get(2,id).favorite()).isTrue();assertThat(service.get(1,id).favorite()).isFalse();
        assertThat(service.notifications(2,null).unreadCount()).isZero();
        service.delete(1,id);assertThat(service.get(2,id).body()).isEqualTo("합성 테스트 본문");
        service.delete(2,id);assertThat(rows("private_notes")).isZero();
        assertThat(rows("private_note_requests")).isEqualTo(1); // retries and rates cannot be reset by mailbox deletion.
    }
    @Test void givenBlockHoldingPairLocks_whenSendCompetes_thenNoPostBlockNote() throws Exception {
        var locked=new CountDownLatch(1);var release=new CountDownLatch(1);var pool=Executors.newFixedThreadPool(2);
        try {
            var blocker=pool.submit(() -> new TransactionTemplate(manager).executeWithoutResult(status -> {
                repository.lockMembers(1,2);repository.block(2,1,NOW);locked.countDown();
                try {if(!release.await(10,TimeUnit.SECONDS)) throw new IllegalStateException("test timeout");}
                catch(InterruptedException e) {Thread.currentThread().interrupt();throw new IllegalStateException(e);}
            }));
            assertThat(locked.await(10,TimeUnit.SECONDS)).isTrue();
            var send=pool.submit(() -> service.send(1,draft(2)));release.countDown();blocker.get(15,TimeUnit.SECONDS);
            assertThatThrownBy(() -> send.get(15,TimeUnit.SECONDS)).hasCauseInstanceOf(ResponseStatusException.class);
            assertThat(rows("private_notes")).isZero();
        } finally {release.countDown();pool.shutdownNow();}
    }
    @Test void givenWithdrawnSender_whenPurge_thenRecipientKeepsTextWithoutMemberLink() {
        UUID id=service.send(1,draft(2)).noteId();
        when(users.getContactMember(1L)).thenReturn(new ContactMember(1L,"탈퇴한 회원",false,true));
        service.withdraw(1);
        NoteView retained=service.get(2,id);
        assertThat(retained.body()).isEqualTo("합성 테스트 본문");assertThat(retained.counterpartId()).isNull();
        assertThat(retained.counterpartNickname()).isEqualTo("탈퇴한 회원");assertThat(retained.canReply()).isFalse();
        assertThat(rows("private_note_mailboxes")).isEqualTo(1);assertThat(rows("private_note_requests")).isZero();
        service.delete(2,id);assertThat(rows("private_notes")).isZero();
    }
    @Test void givenRateAndCalendarExpiry_whenLimitOrTimePassed_thenRejectAndEraseOriginal() {
        for(int i=0;i<10;i++) service.send(1,draft(2));
        assertThatThrownBy(() -> service.send(1,draft(2))).isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException)e).getStatusCode().value()).isEqualTo(429));
        assertThat(repository.expire(NOW.atZone(ZoneOffset.UTC).plusYears(1).toInstant())).isEqualTo(10);
        assertThat(rows("private_notes")).isZero();assertThat(rows("private_note_mailboxes")).isZero();
    }
    @Test void givenMoreThanOneNotificationPage_whenCursorQueried_thenStablePagesAndNoForeignCursorAccess() {
        // Distinct senders avoid the per-sender burst limit.
        for(long sender=10;sender<35;sender++) service.send(sender,draft(2));
        var first=service.notifications(2,null);var second=service.notifications(2,first.nextCursor());
        assertThat(first.content()).hasSize(20);assertThat(second.content()).hasSize(5);
        assertThat(second.nextCursor()).isNull();assertThat(first.unreadCount()).isEqualTo(25);
        assertThat(second.content()).extracting(Notification::noteId).doesNotContainAnyElementsOf(first.content().stream().map(Notification::noteId).toList());
        assertThat(service.notifications(3,first.nextCursor()).content()).isEmpty();
        assertThat(service.list(2,"INBOX",false,0).content()).hasSize(10);
        assertThat(service.list(2,"INBOX",false,2).content()).hasSize(5);
        assertThat(service.list(2,"INBOX",false,0).totalPages()).isEqualTo(3);
    }
    @Test void givenOneHundredSendsOnKstDay_whenNextSendOrNewDay_thenDailyLimitResetsOnlyAtKstMidnight() {
        Instant start=Instant.parse("2026-10-06T13:00:00Z");
        for(int i=0;i<100;i++) {
            var at=new PrivateNoteService(repository,users,stream,Clock.fixed(start.plusSeconds(i*61L),ZoneOffset.UTC),manager,
                    mock(org.springframework.context.ApplicationEventPublisher.class));
            at.send(1,draft(2));
        }
        assertThatThrownBy(() -> service.send(1,draft(2))).isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException)e).getStatusCode().value()).isEqualTo(429));
        var nextDay=new PrivateNoteService(repository,users,stream,Clock.fixed(Instant.parse("2026-10-06T15:00:00Z"),ZoneOffset.UTC),manager,
                mock(org.springframework.context.ApplicationEventPublisher.class));
        assertThat(nextDay.send(1,draft(2)).noteId()).isNotNull();
        assertThat(rows("private_notes")).isEqualTo(101);
    }
    @Test void givenDeletedOriginal_whenCleanupRuns_thenRetryMarkerProtectsRatesUntilFortyEightHours() {
        var input=draft(2);UUID id=service.send(1,input).noteId();
        service.delete(1,id);service.delete(2,id);
        assertThatThrownBy(() -> service.send(1,input)).isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException)e).getStatusCode().value()).isEqualTo(410));
        repository.expire(NOW.plusSeconds(172800));
        assertThat(rows("private_note_requests")).isEqualTo(1);
        repository.expire(NOW.plusSeconds(172801));
        assertThat(rows("private_note_requests")).isZero();
    }
    @Test void givenBlockedSender_whenDetailQueried_thenReplyIsUnavailableWithoutHidingRetainedText() {
        UUID id=service.send(1,draft(2)).noteId();service.block(2,1);
        assertThat(service.get(2,id).canReply()).isFalse();
        assertThat(service.get(2,id).body()).isEqualTo("합성 테스트 본문");
        service.unblock(2,1);assertThat(service.get(2,id).canReply()).isTrue();
    }
    @Test void givenReply_whenParentNotOwnedOrWrongRecipient_thenRejectAndPreserveValidReply() {
        UUID parent=service.send(1,draft(2)).noteId();
        assertThatThrownBy(() -> service.send(3,new SendNote(1L,"무단 답장",UUID.randomUUID(),parent,null,null)))
                .isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> service.send(2,new SendNote(3L,"다른 상대",UUID.randomUUID(),parent,null,null)))
                .isInstanceOf(ResponseStatusException.class);
        UUID reply=service.send(2,new SendNote(1L,"정상 답장",UUID.randomUUID(),parent,null,null)).noteId();
        assertThat(service.get(1,reply).body()).isEqualTo("정상 답장");
        service.delete(1,parent);service.delete(2,parent);
        assertThat(service.get(1,reply).body()).isEqualTo("정상 답장");
        assertThat(jdbc.queryForObject("SELECT reply_to IS NULL FROM pawbridge_community.private_notes WHERE note_id=?",Boolean.class,reply)).isTrue();
    }
    @Test void givenDeletionHoldingOriginalLock_whenCounterpartWithdraws_thenCompleteWithoutCrossLockDeadlock() throws Exception {
        UUID id=service.send(1,draft(2)).noteId();service.delete(2,id);
        when(users.getContactMember(2L)).thenReturn(new ContactMember(2L,"탈퇴한 회원",false,true));
        var locked=new CountDownLatch(1);var release=new CountDownLatch(1);var pool=Executors.newFixedThreadPool(2);
        try {
            var delete=pool.submit(() -> new TransactionTemplate(manager).executeWithoutResult(status -> {
                repository.lockMembers(1,1);repository.lockNote(id);locked.countDown();
                try {if(!release.await(10,TimeUnit.SECONDS)) throw new IllegalStateException("test timeout");}
                catch(InterruptedException e) {Thread.currentThread().interrupt();throw new IllegalStateException(e);}
                repository.delete(1,id);
            }));
            assertThat(locked.await(10,TimeUnit.SECONDS)).isTrue();
            var withdraw=pool.submit(() -> service.withdraw(2));
            // Observe the real PostgreSQL waiter, not a sleep that assumes the race occurred.
            boolean waiting=false;
            for(int i=0;i<100&&!waiting;i++) {
                waiting=jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM pg_stat_activity WHERE datname='pawbridge' AND wait_event_type='Lock' AND regexp_replace(query, '\\s+', ' ', 'g') LIKE 'SELECT note_id FROM pawbridge_community.private_notes WHERE sender_id%')",Boolean.class);
                if(!waiting) Thread.sleep(20);
            }
            assertThat(waiting).isTrue();release.countDown();
            delete.get(15,TimeUnit.SECONDS);withdraw.get(15,TimeUnit.SECONDS);
            assertThat(rows("private_notes")).isZero();
        } finally {release.countDown();pool.shutdownNow();}
    }
}
