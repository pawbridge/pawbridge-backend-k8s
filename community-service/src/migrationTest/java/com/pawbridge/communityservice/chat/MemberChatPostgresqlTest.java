package com.pawbridge.communityservice.chat;

import static com.pawbridge.communityservice.chat.MemberChatModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.pawbridge.communityservice.client.UserServiceClient;
import com.pawbridge.communityservice.contact.PrivateNoteModels.ContactMember;
import com.pawbridge.communityservice.contact.PrivateNoteRepository;
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
@EnabledIfEnvironmentVariable(named = "MEMBER_CHAT_PG_TEST_PORT", matches = "[0-9]{1,5}")
class MemberChatPostgresqlTest {
    static JdbcTemplate jdbc;
    static DataSourceTransactionManager manager;
    static final Instant NOW = Instant.parse("2026-10-10T05:00:00Z");
    MemberChatRepository repository;
    PrivateNoteRepository contact;
    UserServiceClient users;
    MemberChatSignals signals;
    MemberChatService service;

    @BeforeAll static void guardedDatabase() {
        var source = new DriverManagerDataSource("jdbc:postgresql://127.0.0.1:"
                + System.getenv("MEMBER_CHAT_PG_TEST_PORT") + "/pawbridge", "postgres", "local_pg_test_only");
        jdbc = new JdbcTemplate(source);
        assertThat(jdbc.queryForList("SELECT marker FROM migration_test_guard.guard", String.class))
                .containsExactly("services-pg-disposable");
        jdbc.execute("CREATE SCHEMA IF NOT EXISTS pawbridge_community");
        Flyway.configure().dataSource(source).defaultSchema("pawbridge_community")
                .locations("classpath:db/postgresql").cleanDisabled(true).loggers(new String[0]).load().migrate();
        manager = new DataSourceTransactionManager(source);
    }

    @BeforeEach void prepare() {
        // Deletions are allowed only after the disposable database guard above succeeds.
        jdbc.execute("DELETE FROM pawbridge_community.member_chat_rooms");
        jdbc.execute("DELETE FROM pawbridge_community.member_chat_requests");
        jdbc.execute("DELETE FROM pawbridge_community.private_note_blocks");
        repository = new MemberChatRepository(jdbc);
        contact = new PrivateNoteRepository(jdbc);
        users = mock(UserServiceClient.class);
        signals = mock(MemberChatSignals.class);
        when(users.getContactMember(anyLong())).thenAnswer(call ->
                new ContactMember(call.getArgument(0), "합성 회원", true, false));
        service = at(NOW);
    }

    MemberChatService at(Instant now) {
        return new MemberChatService(repository, contact, users, signals, Clock.fixed(now, ZoneOffset.UTC), manager);
    }
    Send draft(long recipient) { return new Send(recipient, UUID.randomUUID(), "합성 메시지", null, null); }
    long rows(String table) { return jdbc.queryForObject("SELECT count(*) FROM pawbridge_community." + table, Long.class); }
    void denied(Runnable action, int status) {
        assertThatThrownBy(action::run).isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException)e).getStatusCode().value()).isEqualTo(status));
    }

    @Test void givenConcurrentRetry_whenSameRequestArrivesTwice_thenOneMessageAndOneReceipt() throws Exception {
        var request = draft(2); var start = new CountDownLatch(1); var pool = Executors.newFixedThreadPool(2);
        try {
            Callable<Receipt> action = () -> { start.await(); return service.send(1, request); };
            var first = pool.submit(action); var second = pool.submit(action); start.countDown();
            assertThat(first.get(15, TimeUnit.SECONDS)).isEqualTo(second.get(15, TimeUnit.SECONDS));
            assertThat(rows("member_chat_messages")).isEqualTo(1);
            assertThat(rows("member_chat_requests")).isEqualTo(1);
            verify(signals, times(2)).publish(any());
        } finally { pool.shutdownNow(); }
    }

    @Test void givenConcurrentFirstMessages_whenBothMembersSend_thenOnePairAndOrderedSequences() throws Exception {
        var start = new CountDownLatch(1); var pool = Executors.newFixedThreadPool(2);
        try {
            var first = pool.submit(() -> { start.await(); return service.send(1, draft(2)); });
            var second = pool.submit(() -> { start.await(); return service.send(2, draft(1)); }); start.countDown();
            var a = first.get(15, TimeUnit.SECONDS); var b = second.get(15, TimeUnit.SECONDS);
            assertThat(a.roomId()).isEqualTo(b.roomId());
            assertThat(List.of(a.sequence(), b.sequence())).containsExactlyInAnyOrder(1L, 2L);
            assertThat(rows("member_chat_rooms")).isEqualTo(1);
            assertThat(rows("member_chat_room_members")).isEqualTo(2);
        } finally { pool.shutdownNow(); }
    }

    @Test void givenUnreadMessage_whenGetReadAndHide_thenOwnerOnlyAndNewMessageRestoresVisibility() {
        UUID room = service.send(1, draft(2)).roomId();
        assertThat(service.notifications(2, null).unreadCount()).isEqualTo(1);
        assertThat(service.notifications(2, null).content().get(0).preview()).isNull();
        service.messages(2, room, null, null); service.room(2, room);
        assertThat(service.notifications(2, null).unreadCount()).isEqualTo(1);
        denied(() -> service.messages(3, room, null, null), 404);
        denied(() -> service.read(3, room, 1), 404);
        denied(() -> service.hide(3, room), 404);
        denied(() -> service.read(2, room, 2), 400);
        service.read(2, room, 1); service.read(2, room, 0);
        assertThat(service.room(1, room).counterpartReadThrough()).isEqualTo(1);
        service.hide(2, room);
        assertThat(service.rooms(2, null).content()).isEmpty();
        assertThat(service.rooms(1, null).content()).hasSize(1);
        service.send(1, draft(2));
        assertThat(service.rooms(2, null).content()).hasSize(1);
        assertThat(service.notifications(2, null).unreadCount()).isEqualTo(1);
    }

    @Test void givenSixtyMessages_whenHistoryOrRecoveryQueried_thenContiguousFiftyAndRemainingTen() {
        UUID room = null;
        for (int n = 0; n < 60; n++) room = service.send(1, draft(2)).roomId();
        var latest = service.messages(2, room, null, null);
        assertThat(latest.content()).hasSize(50);
        assertThat(latest.content().get(0).sequence()).isEqualTo(11);
        assertThat(service.messages(2, room, latest.nextCursor(), null).content()).hasSize(10);
        var recovered = service.messages(2, room, null, 0L);
        assertThat(recovered.content()).hasSize(50);
        assertThat(recovered.content().get(0).sequence()).isEqualTo(1);
        assertThat(service.messages(2, room, null, recovered.nextCursor()).content()).hasSize(10);
        UUID lastRoom = room;
        denied(() -> service.send(1, draft(2)), 429);
        denied(() -> service.messages(2, lastRoom, 1L, 1L), 400);
    }

    @Test void givenTwelveRooms_whenCursorQueried_thenTenAndTwoWithoutForeignCursorData() {
        for (long sender = 10; sender < 22; sender++) service.send(sender, draft(2));
        var first = service.rooms(2, null); var second = service.rooms(2, first.nextCursor());
        assertThat(first.content()).hasSize(10); assertThat(second.content()).hasSize(2);
        assertThat(second.nextCursor()).isNull();
        assertThat(second.content()).extracting(Room::roomId)
                .doesNotContainAnyElementsOf(first.content().stream().map(Room::roomId).toList());
        assertThat(service.rooms(3, first.nextCursor()).content()).isEmpty();
    }

    @Test void givenBlockedPair_whenChatSend_thenRejectAndDoNotCreateRoom() {
        new TransactionTemplate(manager).executeWithoutResult(status -> {
            contact.lockMembers(1, 2); contact.block(2, 1, NOW);
        });
        denied(() -> service.send(1, draft(2)), 403);
        assertThat(rows("member_chat_rooms")).isZero();
    }

    @Test void givenBlockHoldingSharedPairLocks_whenChatSendCompetes_thenNoPostBlockMessage() throws Exception {
        var locked = new CountDownLatch(1); var release = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var blocker = pool.submit(() -> new TransactionTemplate(manager).executeWithoutResult(status -> {
                contact.lockMembers(1, 2); contact.block(2, 1, NOW); locked.countDown();
                try {
                    if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Test timeout");
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt(); throw new IllegalStateException(interrupted);
                }
            }));
            assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
            var sender = pool.submit(() -> service.send(1, draft(2)));
            release.countDown(); blocker.get(15, TimeUnit.SECONDS);
            assertThatThrownBy(() -> sender.get(15, TimeUnit.SECONDS)).hasCauseInstanceOf(ResponseStatusException.class);
            assertThat(rows("member_chat_rooms")).isZero();
            assertThat(rows("member_chat_messages")).isZero();
        } finally { release.countDown(); pool.shutdownNow(); }
    }

    @Test void givenOneThousandRequestsOnKstDay_whenSendBeforeAndAfterMidnight_thenDailyLimitResets() {
        UUID room = service.send(1, draft(2)).roomId();
        // Synthetic prior requests avoid a thousand unrelated transport/clock iterations.
        jdbc.update("""
                INSERT INTO pawbridge_community.member_chat_requests
                (sender_id,request_id,request_hash,room_id,sequence,created_at)
                SELECT 1,gen_random_uuid(),repeat('0',64),?::uuid,1,?::timestamptz - interval '2 hours'
                FROM generate_series(1,999)
                """, room, java.sql.Timestamp.from(NOW));
        denied(() -> service.send(1, draft(2)), 429);
        Instant midnight = NOW.atZone(ZoneId.of("Asia/Seoul")).toLocalDate().plusDays(1)
                .atStartOfDay(ZoneId.of("Asia/Seoul")).toInstant();
        assertThat(at(midnight).send(1, draft(2)).sequence()).isEqualTo(2);
    }

    @Test void givenExpiredOriginal_whenSameKeyRetried_thenGoneWithoutFreshMessage() {
        Send input = draft(2);
        UUID room = service.send(1, input).roomId();
        Instant expired = NOW.atZone(ZoneOffset.UTC).plusYears(1).toInstant();
        denied(() -> at(expired).send(1, input), 410);
        assertThat(repository.membership(1, room).orElseThrow().latestSequence()).isEqualTo(1);
        assertThat(rows("member_chat_requests")).isEqualTo(1);
    }

    @Test void givenExpiredMessages_whenChatFlagDisabled_thenLifecycleStillPurgesAndQueriesExcludeExpiredText() {
        UUID room = service.send(1, draft(2)).roomId();
        Instant expired = NOW.atZone(ZoneOffset.UTC).plusYears(1).toInstant();
        assertThat(at(expired).messages(2, room, null, null).content()).isEmpty();
        var lifecycle = new MemberChatDataLifecycle(repository, Clock.fixed(expired, ZoneOffset.UTC), manager);
        lifecycle.expire();
        assertThat(rows("member_chat_messages")).isZero();
    }

    @Test void givenWithdrawal_whenLifecycleReceivesEvent_thenRetainCounterpartTextWithoutSenderLinkAndFinallyErase() {
        UUID room = service.send(1, draft(2)).roomId();
        var lifecycle = new MemberChatDataLifecycle(repository, Clock.fixed(NOW, ZoneOffset.UTC), manager);
        new TransactionTemplate(manager).executeWithoutResult(status -> lifecycle.withdraw(new Withdrawn(1)));
        var retained = service.room(2, room);
        assertThat(retained.counterpartId()).isNull(); assertThat(retained.counterpartNickname()).isEqualTo("탈퇴한 회원");
        assertThat(retained.canSend()).isFalse();
        assertThat(service.messages(2, room, null, null).content().get(0).senderId()).isNull();
        denied(() -> service.messages(1, room, null, null), 404);
        new TransactionTemplate(manager).executeWithoutResult(status -> lifecycle.withdraw(new Withdrawn(2)));
        assertThat(rows("member_chat_rooms")).isZero(); assertThat(rows("member_chat_messages")).isZero();
    }
}
