package com.pawbridge.backend;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pawbridge.backend.animal.api.AnimalFavoriteEvents;
import com.pawbridge.backend.animal.api.AnimalFavoriteEvents.CompensationEnvelope;
import com.pawbridge.backend.animal.api.AnimalFavoriteEvents.FavoriteEvent;
import com.pawbridge.backend.animal.internal.favorite.infrastructure.JdbcAnimalFavoriteEvents;
import com.pawbridge.backend.user.internal.favoritedelivery.infrastructure.JdbcFavoriteEventDelivery;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.sql.Connection;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;


/** Candidate SQL/atomicity observations in a separately owned PostgreSQL; not a legacy JPA oracle. */
@Tag("owned-postgresql")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Execution(ExecutionMode.SAME_THREAD)
@Timeout(20)
class OwnedPostgresqlFavoriteDeliveryTest {
    private static final LocalDateTime BEFORE = LocalDateTime.of(2020, 1, 2, 3, 4, 5, 123456000);
    private static final String ADDED = "FAVORITE_ADDED", REMOVED = "FAVORITE_REMOVED";
    private static final ObjectMapper JSON = new ObjectMapper();
    private Settings settings;
    private HikariDataSource pool;
    private JdbcTemplate writer;
    private JdbcTemplate admin;
    private TransactionTemplate transactions;
    private AnimalFavoriteEvents actor;
    private long userFloor;
    private long animalFloor;
    private long nextKey;
    private final Set<Long> users = new LinkedHashSet<>();
    private final Set<Long> animals = new LinkedHashSet<>();
    private final Set<String> events = new LinkedHashSet<>();
    private Map<String, List<String>> baseline;

    @BeforeAll void openOwnedFixture() {
        observed("fixture identity", () -> {
            settings = Settings.load();
            // Local file/URL checks precede any connection; server identity precedes admin/seed access.
            var restricted = dataSource(settings.username(), settings.password());
            verifyServer(new JdbcTemplate(restricted), settings.username(), false);
            admin = new JdbcTemplate(dataSource(settings.adminUsername(), settings.adminPassword()));
            admin.setQueryTimeout(4);
            verifyServer(admin, settings.adminUsername(), true);
            HikariConfig config = new HikariConfig();
            config.setJdbcUrl(settings.jdbcUrl());
            config.setUsername(settings.username());
            config.setPassword(settings.password());
            config.setMaximumPoolSize(1);
            config.setMinimumIdle(0);
            config.setConnectionTimeout(2000);
            config.setValidationTimeout(1000);
            config.setInitializationFailTimeout(2000);
            config.setConnectionInitSql("SET statement_timeout='4s'");
            config.addDataSourceProperty("connectTimeout", "3");
            config.addDataSourceProperty("socketTimeout", "5");
            config.setPoolName("owned-favorite-delivery");
            pool = new HikariDataSource(config);
            writer = new JdbcTemplate(pool);
            writer.setQueryTimeout(4);
            transactions = tx(pool);
            actor = new JdbcAnimalFavoriteEvents(writer, transactions);
            nextKey = 8_000_000_000L + (UUID.randomUUID().getMostSignificantBits() & 0x0fffffffL) * 100;
        });
    }

    @BeforeEach void rememberBaseline() {
        observed("baseline", () -> {
            verifyServer(admin, settings.adminUsername(), true);
            users.clear(); animals.clear(); events.clear();
            baseline = snapshot();
            userFloor = scalar("SELECT COALESCE(max(outbox_event_id),0) FROM pawbridge_user.outbox_events");
            animalFloor = scalar("SELECT COALESCE(max(id),0) FROM pawbridge_animal.outbox_events");
        });
    }

    @AfterEach void removeOnlyAcknowledgedOwnedRows() {
        observed("owned cleanup", () -> {
            verifyServer(admin, settings.adminUsername(), true);
            // Recovery creates two UUIDs after our seed. Discover them only through our own aggregate IDs.
            for (long user : users) {
                for (Map<String, Object> row : admin.queryForList(
                        "SELECT event_id,payload::jsonb->>'eventId' AS payload_id FROM pawbridge_animal.outbox_events WHERE aggregate_id=?",
                        Long.toString(user))) {
                    events.add((String) row.get("event_id"));
                    if (row.get("payload_id") instanceof String payloadId) events.add(payloadId);
                }
            }
            RuntimeException cleanupFailure = null;
            for (String id : events) {
                for (String table : List.of("pawbridge_user.processed_events", "pawbridge_animal.processed_events",
                        "pawbridge_user.outbox_events", "pawbridge_animal.outbox_events")) {
                    try { admin.update("DELETE FROM " + table + " WHERE event_id=?", id); }
                    catch (RuntimeException failure) { cleanupFailure = failure; }
                }
            }
            for (long user : users) {
                try { admin.update("DELETE FROM pawbridge_user.favorites WHERE user_id=?", user); }
                catch (RuntimeException failure) { cleanupFailure = failure; }
            }
            for (long animal : animals) {
                try { admin.update("DELETE FROM pawbridge_animal.animals WHERE id=?", animal); }
                catch (RuntimeException failure) { cleanupFailure = failure; }
            }
            check(cleanupFailure == null, "Every owned cleanup operation must succeed");
            check(snapshot().equals(baseline), "All six business tables must return to their baseline");
            released();
        });
    }

    @AfterAll void closePool() {
        if (pool != null) {
            pool.close();
            check(pool.isClosed(), "The owned writer pool must close");
        }
    }

    @Test void givenAddedAndRemoved_whenPollingWithOneConnection_thenCountsReceiptsAndFixedFloorAreDurable() {
        observed("normal delivery", () -> {
            long user = user(), animal = animal(0);
            Source legacy = source(user, animal, ADDED);
            long fixedFloor = legacy.sequence();
            Source added = source(user, animal, ADDED), removed = source(user, animal, REMOVED);
            check(!added.event().eventId().equals(added.outboxId()), "Payload and Outbox UUIDs are distinct");
            var poller = poller(actor);
            check(poller.poll(fixedFloor, animalFloor, 1) == 1, "The first aggregate head must commit");
            check(count(animal) == 1 && receipt("pawbridge_animal", added.event().eventId()) == 1,
                    "Added count and payload receipt must commit together");
            check(receipt("pawbridge_user", added.outboxId()) == 1, "The User Outbox UUID must be acknowledged");
            check(poller.poll(fixedFloor, animalFloor, 1) == 1, "The next aggregate head must not starve");
            check(count(animal) == 0 && receipt("pawbridge_animal", removed.event().eventId()) == 1,
                    "Removed count and its independent receipt must commit");
            check(receipt("pawbridge_user", legacy.outboxId()) == 0
                    && receipt("pawbridge_animal", legacy.event().eventId()) == 0, "The legacy floor must not replay");
            check(poller.poll(fixedFloor, animalFloor, 1) == 0, "Acknowledged rows must not replay");
            released();
        });
    }

    @Test void givenCommittedActorAndLostResponse_whenRetryingSamePayload_thenTheCountChangesOnce() {
        observed("uncertain actor response", () -> {
            long user = user(), animal = animal(0);
            Source source = source(user, animal, ADDED);
            AtomicInteger calls = new AtomicInteger();
            AnimalFavoriteEvents lost = new Forwarding(actor) {
                @Override public void applyFavorite(FavoriteEvent event) {
                    check(!TransactionSynchronizationManager.isActualTransactionActive(), "Actor calls must be outside User transactions");
                    super.applyFavorite(event);
                    if (calls.getAndIncrement() == 0) throw unavailable();
                }
            };
            check(poller(lost).poll(userFloor, animalFloor, 10) == 1, "A durable actor receipt must resolve response loss");
            check(calls.get() == 2 && count(animal) == 1, "The replay must reach the actor without a second count change");
            check(receipt("pawbridge_animal", source.event().eventId()) == 1
                    && receipt("pawbridge_user", source.outboxId()) == 1, "Both acknowledgements must be durable");
            released();
        });
    }

    @Test void givenMissingAnimal_whenFourAttemptsFail_thenCompensationResolvesTheCommittedUserFavoriteOnce() {
        observed("missing animal recovery", () -> {
            long user = user(), missing = key();
            favorite(user, missing);
            Source source = source(user, missing, ADDED);
            AtomicInteger attempts = new AtomicInteger(), recovery = new AtomicInteger();
            AnimalFavoriteEvents counted = new Forwarding(actor) {
                @Override public void applyFavorite(FavoriteEvent event) {
                    check(favorites(user, missing) == 1, "The original User favorite survives all actor attempts");
                    attempts.incrementAndGet(); super.applyFavorite(event);
                }
                @Override public void recoverAdded(FavoriteEvent event) { recovery.incrementAndGet(); super.recoverAdded(event); }
            };
            var poller = poller(counted);
            check(poller.poll(userFloor, animalFloor, 10) == 2, "Source delivery and compensation ACK must both complete");
            check(attempts.get() == 4 && recovery.get() == 1 && favorites(user, missing) == 0,
                    "Only recovery may remove the committed User pair");
            check("FAVORITE_ADDED_COMPENSATED".equals(type("pawbridge_animal", source.event().eventId())),
                    "The original payload receipt must record compensation resolution");
            List<Map<String, Object>> rows = admin.queryForList(
                    "SELECT event_id,payload::jsonb->>'eventId' AS payload_id,payload::jsonb->>'originalEventId' AS original_id FROM pawbridge_animal.outbox_events WHERE aggregate_id=?",
                    Long.toString(user));
            check(rows.size() == 1, "Exactly one compensation envelope must be committed");
            String outbox = (String) rows.getFirst().get("event_id"), payload = (String) rows.getFirst().get("payload_id");
            check(!outbox.equals(payload) && source.event().eventId().equals(rows.getFirst().get("original_id")),
                    "Compensation identities must remain independent and reference the source payload");
            check("ROLLBACK_FAVORITE_ADDED".equals(type("pawbridge_user", payload))
                    && "LOCAL_COMPENSATION_DELIVERED".equals(type("pawbridge_animal", outbox)),
                    "User compensation and Actor ACK receipts must commit");
            check(poller.poll(userFloor, animalFloor, 10) == 0 && recovery.get() == 1, "Recovery must not repeat");
            released();
        });
    }

    @Test void givenCompensationAckLoss_whenPollingAgain_thenAReaddedFavoriteIsNotDeletedAgain() {
        observed("compensation ACK loss", () -> {
            long user = user(), missing = key();
            favorite(user, missing);
            Source source = source(user, missing, ADDED);
            actor.recoverAdded(source.event());
            AtomicBoolean ackWasLost = new AtomicBoolean();
            AtomicInteger acknowledgements = new AtomicInteger();
            AnimalFavoriteEvents lost = new Forwarding(actor) {
                @Override public void acknowledgeCompensation(CompensationEnvelope envelope) {
                    super.acknowledgeCompensation(envelope);
                    acknowledgements.incrementAndGet();
                    if (ackWasLost.compareAndSet(false, true)) {
                        check(favorites(user, missing) == 0, "The first compensation transaction must commit before ACK");
                        favorite(user, missing);
                        throw unavailable();
                    }
                }
            };
            var poller = poller(lost);
            check(poller.poll(userFloor, animalFloor, 10) == 2 && acknowledgements.get() == 2,
                    "The ACK response loss must retry the same compensation within this poll");
            check(favorites(user, missing) == 1, "A receipt replay must preserve a later favorite");
            check(poller.poll(userFloor, animalFloor, 10) == 0 && favorites(user, missing) == 1,
                    "Later polls must also preserve the readded pair");
            released();
        });
    }

    @Test void givenTwoConcurrentActorTransactions_whenPayloadIdsMatch_thenOnlyOneReceiptAndIncrementCommit() {
        observed("concurrent actor claim", () -> {
            long animal = animal(0), user = user();
            FavoriteEvent event = event(user, animal, ADDED);
            CountDownLatch bothClaiming = new CountDownLatch(2), release = new CountDownLatch(1);
            Set<Integer> backends = ConcurrentHashMap.newKeySet();
            DataSource independent = dataSource(settings.username(), settings.password());
            JdbcTemplate concurrent = new JdbcTemplate(independent) {
                @Override public int update(String sql, Object... args) {
                    if (sql.startsWith("INSERT INTO pawbridge_animal.processed_events")) {
                        backends.add(queryForObject("SELECT pg_backend_pid()", Integer.class));
                        bothClaiming.countDown(); await(release);
                    }
                    return super.update(sql, args);
                }
            };
            concurrent.setQueryTimeout(4);
            var concurrentActor = new JdbcAnimalFavoriteEvents(concurrent, tx(independent));
            ExecutorService executor = Executors.newFixedThreadPool(2);
            List<Future<?>> work = new ArrayList<>();
            try {
                work.add(executor.submit(() -> concurrentActor.applyFavorite(event)));
                work.add(executor.submit(() -> concurrentActor.applyFavorite(event)));
                check(bothClaiming.await(4, TimeUnit.SECONDS), "Both physical transactions must reach the receipt claim");
                release.countDown();
                for (Future<?> future : work) future.get(5, TimeUnit.SECONDS);
                check(backends.size() == 2 && count(animal) == 1 && receipt("pawbridge_animal", event.eventId()) == 1,
                        "The real concurrent unique claims must commit only one mutation");
            } finally {
                release.countDown(); work.forEach(future -> future.cancel(true));
                executor.shutdownNow();
                check(executor.awaitTermination(5, TimeUnit.SECONDS), "All concurrent actor workers must terminate");
            }
            released();
        });
    }

    @Test void givenAnUncommittedLowerSequence_whenHigherAggregateCommitsFirst_thenTheFixedFloorEventuallyDeliversBoth() {
        observed("late lower sequence within a finite epoch", () -> {
            long firstUser = user(), secondUser = user(), thirdUser = user();
            long firstAnimal = animal(0), secondAnimal = animal(0), thirdAnimal = animal(0);
            FavoriteEvent first = event(firstUser, firstAnimal, ADDED);
            String firstOutbox = id();
            try (Connection lower = dataSource(settings.adminUsername(), settings.adminPassword()).getConnection()) {
                lower.setAutoCommit(false);
                long lowSequence;
                try (var statement = lower.prepareStatement(sourceInsert())) {
                    statement.setQueryTimeout(4);
                    bindSource(statement, first, firstOutbox);
                    try (var rows = statement.executeQuery()) { check(rows.next(), "The lower owned row must be inserted"); lowSequence = rows.getLong(1); }
                }
                Source higher = source(secondUser, secondAnimal, ADDED);
                Source epochEnd = source(thirdUser, thirdAnimal, ADDED);
                check(lowSequence < higher.sequence() && higher.sequence() < epochEnd.sequence(),
                        "Two committed aggregate heads must bound the first epoch above the uncommitted row");
                var poller = poller(actor);
                check(poller.poll(userFloor, animalFloor, 1) == 1 && count(secondAnimal) == 1 && count(firstAnimal) == 0,
                        "The first poll must capture an upper bound and process the first visible aggregate");
                long firstTailAnimal = animal(0);
                source(user(), firstTailAnimal, ADDED);
                lower.commit();
                check(poller.poll(userFloor, animalFloor, 1) == 1 && count(thirdAnimal) == 1 && count(firstTailAnimal) == 0,
                        "A tail added after capture must remain outside the unfinished bounded epoch");
                // Keep a rising tail present. A moving delivery checkpoint would never revisit the lower ID.
                // A finite selection epoch may require one empty read to wrap, then must revisit its fixed floor.
                for (int boundedPoll = 0; boundedPoll < 2 && count(firstAnimal) == 0; boundedPoll++) {
                    source(user(), animal(0), ADDED);
                    poller.poll(userFloor, animalFloor, 1);
                }
                check(count(firstAnimal) == 1 && receipt("pawbridge_user", firstOutbox) == 1,
                        "Continuous higher arrivals must not starve a committed lower ID beyond the next finite cycle");
            }
            released();
        });
    }

    @Test void givenAnAlwaysFailingFirstAggregate_whenPollingAgainWithLimitOne_thenAnotherAggregateMakesProgress() {
        observed("User aggregate fairness", () -> {
            long failedUser = user(), healthyUser = user(), failedAnimal = animal(0), healthyAnimal = animal(0);
            Source failed = source(failedUser, failedAnimal, REMOVED);
            Source healthy = source(healthyUser, healthyAnimal, ADDED);
            String original = userOutbox(failed.outboxId());
            AtomicInteger attempts = new AtomicInteger();
            AnimalFavoriteEvents rejecting = new Forwarding(actor) {
                @Override public void applyFavorite(FavoriteEvent event) {
                    if (event.userId() == failedUser) {
                        check(!TransactionSynchronizationManager.isActualTransactionActive(), "Failed module retries must remain outside User transactions");
                        attempts.incrementAndGet();
                        throw unavailable();
                    }
                    super.applyFavorite(event);
                }
            };
            var poller = poller(rejecting);
            check(poller.poll(userFloor, animalFloor, 1) == 0 && attempts.get() == 4,
                    "The first unresolved removed event must use exactly four bounded attempts");
            released();
            check(poller.poll(userFloor, animalFloor, 1) == 1 && count(healthyAnimal) == 1 && attempts.get() == 4,
                    "The same poller must select a different aggregate after the failed prefix");
            check(receipt("pawbridge_user", healthy.outboxId()) == 1
                    && receipt("pawbridge_user", failed.outboxId()) == 0
                    && receipt("pawbridge_animal", failed.event().eventId()) == 0
                    && original.equals(userOutbox(failed.outboxId())),
                    "Fairness must preserve the unresolved source row without inventing receipts");
            released();
        });
    }

    @Test void givenAPoisonCompensationHead_whenSelectingTwoAggregates_thenHealthyWorkCommitsWithoutPassingItsBlockedSuccessor() {
        observed("Animal poison head isolation", () -> {
            long blockedUser = user(), healthyUser = user(), blockedAnimal = animal(0), healthyAnimal = animal(0);
            favorite(blockedUser, blockedAnimal); favorite(healthyUser, healthyAnimal);
            CompensationSeed poison = compensationSeed(blockedUser, blockedAnimal, true);
            CompensationSeed healthy = compensationSeed(healthyUser, healthyAnimal, false);
            CompensationSeed blockedSuccessor = compensationSeed(blockedUser, blockedAnimal, false);
            String poisonBefore = animalOutbox(poison.outboxId()), successorBefore = animalOutbox(blockedSuccessor.outboxId());
            AtomicReference<List<CompensationEnvelope>> selected = new AtomicReference<>();
            AnimalFavoriteEvents observedActor = new Forwarding(actor) {
                @Override public List<CompensationEnvelope> pendingCompensations(long floor, int limit) {
                    List<CompensationEnvelope> rows = super.pendingCompensations(floor, limit);
                    selected.set(rows); return rows;
                }
            };
            check(poller(observedActor).poll(userFloor, animalFloor, 2) == 1,
                    "A malformed head in one aggregate must not discard a healthy selected aggregate");
            check(selected.get() != null && selected.get().size() == 1
                    && healthy.outboxId().equals(selected.get().getFirst().outboxEventId()),
                    "The valid envelope must be returned while only the malformed row is skipped");
            check(favorites(healthyUser, healthyAnimal) == 0
                    && "ROLLBACK_FAVORITE_ADDED".equals(type("pawbridge_user", healthy.payloadId()))
                    && "LOCAL_COMPENSATION_DELIVERED".equals(type("pawbridge_animal", healthy.outboxId())),
                    "Healthy User compensation and Animal acknowledgement must both commit");
            check(favorites(blockedUser, blockedAnimal) == 1
                    && receipt("pawbridge_animal", poison.outboxId()) == 0
                    && receipt("pawbridge_animal", blockedSuccessor.outboxId()) == 0
                    && receipt("pawbridge_user", blockedSuccessor.payloadId()) == 0
                    && poisonBefore.equals(animalOutbox(poison.outboxId()))
                    && successorBefore.equals(animalOutbox(blockedSuccessor.outboxId())),
                    "A poison predecessor and its valid same-aggregate successor must remain unacknowledged and unchanged");
            released();
        });
    }

    @Test void givenAnUpdateFailureAfterClaim_whenRollingBack_thenReceiptIsAbsentAndNonpositiveRemovalKeepsAuditTime() {
        observed("atomic rollback and positive-only decrement", () -> {
            long user = user(), overflow = animal(Integer.MAX_VALUE);
            FavoriteEvent failing = event(user, overflow, ADDED);
            boolean failed = false;
            try { actor.applyFavorite(failing); } catch (RuntimeException expected) { failed = true; }
            check(failed && receipt("pawbridge_animal", failing.eventId()) == 0 && count(overflow) == Integer.MAX_VALUE,
                    "A real PostgreSQL integer overflow must roll back the preceding receipt claim");
            for (int initial : List.of(0, -2, 1)) {
                long animal = animal(initial);
                FavoriteEvent removed = event(user, animal, REMOVED);
                actor.applyFavorite(removed);
                check(count(animal) == (initial > 0 ? initial - 1 : initial), "Removal must only decrement positive counts");
                check(receipt("pawbridge_animal", removed.eventId()) == 1, "Each successful no-op removal is still acknowledged");
                LocalDateTime audit = admin.queryForObject("SELECT updated_at FROM pawbridge_animal.animals WHERE id=?",
                        (row, index) -> row.getTimestamp(1).toLocalDateTime(), animal);
                check(initial > 0 ? !BEFORE.equals(audit) : BEFORE.equals(audit),
                        "Only a real count mutation may change the Animal audit timestamp");
            }
            released();
        });
    }

    private JdbcFavoriteEventDelivery poller(AnimalFavoriteEvents events) {
        return new JdbcFavoriteEventDelivery(writer, transactions, events, JSON);
    }
    private TransactionTemplate tx(DataSource source) {
        var template = new TransactionTemplate(new JdbcTransactionManager(source));
        template.setTimeout(5);
        return template;
    }
    private DriverManagerDataSource dataSource(String user, String password) {
        var source = new DriverManagerDataSource(settings.jdbcUrl(), user, password);
        Properties timeouts = new Properties();
        timeouts.setProperty("connectTimeout", "3"); timeouts.setProperty("socketTimeout", "5");
        source.setConnectionProperties(timeouts);
        return source;
    }
    private long key() { return ++nextKey; }
    private long user() {
        long value = key();
        check(scalar("SELECT (SELECT count(*) FROM pawbridge_user.favorites WHERE user_id=?) + (SELECT count(*) FROM pawbridge_user.outbox_events WHERE aggregate_id=?) + (SELECT count(*) FROM pawbridge_animal.outbox_events WHERE aggregate_id=?)",
                value, Long.toString(value), Long.toString(value)) == 0, "The synthetic aggregate key must be unused");
        users.add(value); return value;
    }
    private long animal(int count) {
        long value = key();
        check(admin.update("INSERT INTO pawbridge_animal.animals(id,favorite_count,updated_at) VALUES(?,?,?)",
                value, count, Timestamp.valueOf(BEFORE)) == 1, "Owned Animal seed must be acknowledged");
        animals.add(value); return value;
    }
    private void favorite(long user, long animal) {
        check(users.contains(user), "Only an owned User pair may be seeded");
        check(admin.update("INSERT INTO pawbridge_user.favorites(user_id,animal_id,created_at) VALUES(?,?,?)",
                user, animal, Timestamp.valueOf(BEFORE)) == 1, "Owned favorite seed must be acknowledged");
    }
    private String id() {
        String id = UUID.randomUUID().toString();
        check(scalar("SELECT (SELECT count(*) FROM pawbridge_user.outbox_events WHERE event_id=?) + (SELECT count(*) FROM pawbridge_user.processed_events WHERE event_id=?) + (SELECT count(*) FROM pawbridge_animal.outbox_events WHERE event_id=?) + (SELECT count(*) FROM pawbridge_animal.processed_events WHERE event_id=?)",
                id, id, id, id) == 0, "The synthetic UUID must be unused before claiming ownership");
        events.add(id); return id;
    }
    private FavoriteEvent event(long user, long animal, String type) { return new FavoriteEvent(id(), user, animal, type); }
    private Source source(long user, long animal, String type) throws Exception {
        FavoriteEvent event = event(user, animal, type); String outbox = id();
        Long sequence = admin.queryForObject(sourceInsert(), Long.class, Long.toString(user), outbox, type,
                payload(event), Timestamp.valueOf(BEFORE));
        check(sequence != null, "Owned Outbox seed must be acknowledged");
        return new Source(sequence, outbox, event);
    }
    private CompensationSeed compensationSeed(long user, long animal, boolean poison) throws Exception {
        String outbox = id(), payloadId = poison ? null : id();
        String body = poison ? "{}" : JSON.writeValueAsString(Map.of(
                "eventId", payloadId, "originalEventId", id(), "eventType", "FAVORITE_COMPENSATION_REQUIRED",
                "userId", user, "animalId", animal, "compensationType", "ROLLBACK_FAVORITE_ADDED"));
        Long sequence = admin.queryForObject("INSERT INTO pawbridge_animal.outbox_events(aggregate_id,aggregate_type,event_id,event_type,topic,payload,created_at) "
                + "VALUES(?,'FavoriteCompensation',?,'FAVORITE_COMPENSATION_REQUIRED','user.compensation.events',?,?) RETURNING id",
                Long.class, Long.toString(user), outbox, body, Timestamp.valueOf(BEFORE));
        check(sequence != null, "The owned compensation envelope must be acknowledged");
        return new CompensationSeed(outbox, payloadId);
    }
    private String userOutbox(String id) {
        return admin.queryForObject("SELECT row_to_json(owned_row)::text FROM pawbridge_user.outbox_events owned_row WHERE event_id=?", String.class, id);
    }
    private String animalOutbox(String id) {
        return admin.queryForObject("SELECT row_to_json(owned_row)::text FROM pawbridge_animal.outbox_events owned_row WHERE event_id=?", String.class, id);
    }
    private static String sourceInsert() {
        return "INSERT INTO pawbridge_user.outbox_events(aggregate_id,aggregate_type,event_id,event_type,topic,payload,created_at) VALUES(?,'Favorite',?,?,'user.favorite.events',?::json,?) RETURNING outbox_event_id";
    }
    private static String payload(FavoriteEvent event) throws Exception {
        return JSON.writeValueAsString(Map.of("eventId", event.eventId(), "userId", event.userId(),
                "animalId", event.animalId(), "eventType", event.eventType()));
    }
    private static void bindSource(java.sql.PreparedStatement statement, FavoriteEvent event, String outbox) throws Exception {
        statement.setString(1, Long.toString(event.userId())); statement.setString(2, outbox);
        statement.setString(3, event.eventType()); statement.setString(4, payload(event));
        statement.setTimestamp(5, Timestamp.valueOf(BEFORE));
    }
    private long count(long animal) { return scalar("SELECT favorite_count FROM pawbridge_animal.animals WHERE id=?", animal); }
    private long favorites(long user, long animal) { return scalar("SELECT count(*) FROM pawbridge_user.favorites WHERE user_id=? AND animal_id=?", user, animal); }
    private long receipt(String schema, String id) { return scalar("SELECT count(*) FROM " + schema + ".processed_events WHERE event_id=?", id); }
    private String type(String schema, String id) {
        List<String> values = admin.query("SELECT event_type FROM " + schema + ".processed_events WHERE event_id=?", (row, i) -> row.getString(1), id);
        check(values.size() == 1, "The exact receipt type must exist"); return values.getFirst();
    }
    private long scalar(String sql, Object... args) {
        Long value = admin.queryForObject(sql, Long.class, args);
        check(value != null, "The bounded scalar observation must exist"); return value;
    }
    private Map<String, List<String>> snapshot() {
        Map<String, List<String>> rows = new LinkedHashMap<>();
        for (String entry : List.of("pawbridge_user.favorites:favorite_id", "pawbridge_user.outbox_events:outbox_event_id",
                "pawbridge_user.processed_events:event_id", "pawbridge_animal.animals:id",
                "pawbridge_animal.outbox_events:id", "pawbridge_animal.processed_events:event_id")) {
            String[] part = entry.split(":");
            rows.put(part[0], admin.query("SELECT row_to_json(owned_row)::text FROM (SELECT * FROM " + part[0]
                    + " ORDER BY " + part[1] + ") owned_row", (row, index) -> row.getString(1)));
        }
        return rows;
    }
    private void released() {
        check(!TransactionSynchronizationManager.isActualTransactionActive()
                && !TransactionSynchronizationManager.hasResource(pool), "No User transaction may span the Actor boundary");
        check(pool.getHikariPoolMXBean().getActiveConnections() == 0
                && pool.getHikariPoolMXBean().getThreadsAwaitingConnection() == 0, "The one-slot pool must return every connection");
    }
    private void verifyServer(JdbcTemplate jdbc, String user, boolean owner) {
        jdbc.setQueryTimeout(4);
        Map<String, Object> identity = jdbc.queryForMap("SELECT current_database() AS db,current_user AS actor,session_user AS session,"
                + "current_setting('server_version_num') AS version,(SELECT pg_get_userbyid(datdba) FROM pg_database WHERE datname=current_database()) AS owner");
        check(settings.database().equals(identity.get("db")) && user.equals(identity.get("actor"))
                && user.equals(identity.get("session")) && "170011".equals(identity.get("version")), "Owned PostgreSQL identity must match");
        check(owner ? user.equals(identity.get("owner")) : !user.equals(identity.get("owner")), "Writer and database owner must be separate");
        List<String> markers = jdbc.query("SELECT owner_marker FROM public.pawbridge_owned_fixture WHERE value='favorite-delivery-v1'",
                (row, index) -> row.getString(1));
        check(markers.size() == 1 && settings.ownerMarker().equals(markers.getFirst()), "Owned fixture marker must match");
        if (!owner) {
            Boolean limited = jdbc.queryForObject("SELECT NOT rolsuper AND NOT rolcreatedb AND NOT rolcreaterole FROM pg_roles WHERE rolname=current_user", Boolean.class);
            check(Boolean.TRUE.equals(limited), "The writer role must be unprivileged");
            Boolean ownerMember = jdbc.queryForObject("SELECT pg_has_role(current_user,(SELECT datdba FROM pg_database WHERE datname=current_database()),'MEMBER')", Boolean.class);
            check(Boolean.FALSE.equals(ownerMember), "The writer must not inherit the database owner role");
        }
    }
    private static void await(CountDownLatch latch) {
        try { check(latch.await(5, TimeUnit.SECONDS), "The bounded worker barrier must release"); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw unavailable(); }
    }
    private static void check(boolean condition, String fixedMessage) { if (!condition) throw new AssertionError(fixedMessage); }
    private static IllegalStateException unavailable() { return new IllegalStateException("Synthetic response unavailable"); }
    private static void observed(String phase, Checked action) {
        try { action.run(); }
        catch (AssertionError failure) { throw failure; }
        catch (Throwable failure) { throw new AssertionError("Owned PostgreSQL observation failed: " + phase); }
    }
    private interface Checked { void run() throws Exception; }
    private record Source(long sequence, String outboxId, FavoriteEvent event) { }
    private record CompensationSeed(String outboxId, String payloadId) { }
    private static class Forwarding implements AnimalFavoriteEvents {
        private final AnimalFavoriteEvents delegate;
        Forwarding(AnimalFavoriteEvents delegate) { this.delegate = delegate; }
        @Override public void applyFavorite(FavoriteEvent event) { delegate.applyFavorite(event); }
        @Override public void recoverAdded(FavoriteEvent event) { delegate.recoverAdded(event); }
        @Override public List<CompensationEnvelope> pendingCompensations(long floor, int limit) { return delegate.pendingCompensations(floor, limit); }
        @Override public void acknowledgeCompensation(CompensationEnvelope envelope) { delegate.acknowledgeCompensation(envelope); }
    }
    private record Settings(String jdbcUrl, String database, String username, String password,
            String ownerMarker, String adminUsername, String adminPassword) {
        static Settings load() throws Exception {
            String configured = System.getenv("P6_PG_SETTINGS");
            check(configured != null && !configured.isBlank(), "P6_PG_SETTINGS is required; this tagged task must never skip");
            Path file = Path.of(configured);
            check(file.isAbsolute() && Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)
                    && !Files.isSymbolicLink(file), "Settings must be an absolute regular owned file");
            check(Files.getPosixFilePermissions(file, LinkOption.NOFOLLOW_LINKS).equals(Set.of(
                    PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)), "Settings permissions must be exactly 0600");
            check(((Number) Files.getAttribute(file, "unix:nlink", LinkOption.NOFOLLOW_LINKS)).intValue() == 1,
                    "Settings must not have another hard link");
            check(file.normalize().equals(file), "Settings path must be canonical without dot segments");
            for (Path part = file.getParent(); part != null; part = part.getParent())
                check(!Files.isSymbolicLink(part), "Settings ancestors must not be symbolic links");
            Path parent = file.getParent();
            check(!Files.isSymbolicLink(parent) && Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS)
                    && Files.getOwner(file).equals(Files.getOwner(parent))
                    && System.getProperty("user.name").equals(Files.getOwner(file).getName()), "Settings file and parent must belong to the current user");
            Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(parent, LinkOption.NOFOLLOW_LINKS);
            check(!permissions.contains(PosixFilePermission.GROUP_WRITE) && !permissions.contains(PosixFilePermission.OTHERS_WRITE),
                    "Settings parent must reject writes from other users");
            Properties values = new Properties();
            try (InputStream input = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) { values.load(input); }
            String database = required(values, "database"), url = required(values, "jdbcUrl");
            check(database.matches("pb_favorite_owned_[0-9a-f]{8,32}")
                    && url.matches("jdbc:postgresql://127\\.0\\.0\\.1:[1-9][0-9]{0,4}/" + database), "Only the explicit owned loopback database URL is allowed");
            int port = Integer.parseInt(url.substring(url.lastIndexOf(':') + 1, url.lastIndexOf('/')));
            check(port <= 65535, "The owned database port must be valid");
            String marker = required(values, "ownerMarker"), writer = required(values, "username"), admin = required(values, "adminUsername");
            check(marker.matches("[0-9a-f]{32}") && writer.matches("[a-z][a-z0-9_]{1,62}")
                    && admin.matches("[a-z][a-z0-9_]{1,62}") && !writer.equals(admin), "Owned role and marker settings must be explicit");
            return new Settings(url, database, writer, required(values, "password"), marker,
                    admin, required(values, "adminPassword"));
        }
        private static String required(Properties values, String key) {
            String value = values.getProperty(key);
            check(value != null && !value.isBlank(), "Every required owned setting must be present"); return value;
        }
        @Override public String toString() { return "OwnedPostgresqlSettings[redacted]"; }
    }
}
