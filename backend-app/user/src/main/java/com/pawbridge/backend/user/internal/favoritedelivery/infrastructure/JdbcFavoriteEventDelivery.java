package com.pawbridge.backend.user.internal.favoritedelivery.infrastructure;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pawbridge.backend.animal.api.AnimalFavoriteEvents;
import com.pawbridge.backend.animal.api.AnimalFavoriteEvents.CompensationEnvelope;
import com.pawbridge.backend.animal.api.AnimalFavoriteEvents.FavoriteEvent;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.Calendar;
import java.util.List;
import java.util.Objects;
import java.util.TimeZone;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.jdbc.support.SqlValue;
import org.springframework.transaction.support.TransactionOperations;

/** Explicitly owned poller. Each module call follows the completed User transaction. */
public final class JdbcFavoriteEventDelivery {
    private static final Logger log = LoggerFactory.getLogger(JdbcFavoriteEventDelivery.class);
    private static final String DELIVERED = "LOCAL_FAVORITE_DELIVERED";
    private static final String ADDED = "FAVORITE_ADDED";
    private static final String REMOVED = "FAVORITE_REMOVED";
    private static final String COMPENSATION = "FAVORITE_COMPENSATION_REQUIRED";
    private static final String ROLLBACK_ADDED = "ROLLBACK_FAVORITE_ADDED";
    private static final String ROLLBACK_REMOVED = "ROLLBACK_FAVORITE_REMOVED";
    private static final String COLUMNS = "outbox_event_id,event_id,aggregate_type,aggregate_id,event_type,topic,payload";
    private static final String EPOCH_UPPER = """
            SELECT COALESCE(MAX(outbox_event_id),?) FROM pawbridge_user.outbox_events
            WHERE outbox_event_id>? AND aggregate_type='Favorite' AND topic='user.favorite.events'
            """;
    private static final String PENDING = """
            WITH pending AS (
                SELECT o.*,row_number() OVER (PARTITION BY o.aggregate_id ORDER BY o.outbox_event_id) AS pending_rank
                FROM pawbridge_user.outbox_events o
                WHERE o.outbox_event_id > ? AND o.outbox_event_id <= ?
                  AND o.aggregate_type='Favorite' AND o.topic='user.favorite.events'
                  AND NOT EXISTS (SELECT 1 FROM pawbridge_user.processed_events p
                                  WHERE p.event_id=o.event_id AND p.event_type='LOCAL_FAVORITE_DELIVERED')
            ) SELECT
            """ + COLUMNS + " FROM pending WHERE pending_rank=1 AND outbox_event_id>? ORDER BY outbox_event_id LIMIT ?";
    private static final String ENVELOPE = "SELECT " + COLUMNS + " FROM pawbridge_user.outbox_events WHERE outbox_event_id=?";
    private static final String RECEIPT = "SELECT event_type FROM pawbridge_user.processed_events WHERE event_id=?";
    private static final String INSERT_RECEIPT = """
            INSERT INTO pawbridge_user.processed_events(event_id,event_type,processed_at)
            VALUES(?,?,?) ON CONFLICT(event_id) DO NOTHING
            """;
    private final JdbcOperations jdbc;
    private final TransactionOperations transactions;
    private final AnimalFavoriteEvents animals;
    private final ObjectMapper json;
    private final Runnable retryDelay;
    private volatile boolean stopped;
    private long currentFloor = -1;
    private long selectionCursor;
    private long epochUpper;
    private boolean epochExhausted = true;

    public JdbcFavoriteEventDelivery(JdbcOperations jdbc, TransactionOperations transactions,
            AnimalFavoriteEvents animals, ObjectMapper json) {
        this(jdbc, transactions, animals, json, JdbcFavoriteEventDelivery::sleepBeforeRetry);
    }

    // The delay is a direct test seam; there is no scheduler or additional delivery abstraction here.
    JdbcFavoriteEventDelivery(JdbcOperations jdbc, TransactionOperations transactions,
            AnimalFavoriteEvents animals, ObjectMapper json, Runnable retryDelay) {
        this.jdbc = Objects.requireNonNull(jdbc);
        this.transactions = Objects.requireNonNull(transactions);
        this.animals = Objects.requireNonNull(animals);
        this.json = Objects.requireNonNull(json);
        this.retryDelay = Objects.requireNonNull(retryDelay);
    }

    /** Stop is cooperative. An already committed operation is safely replayed if its final ACK is skipped. */
    public void stop() { stopped = true; }

    /** Floors are fixed exclusive handoff boundaries, never moving checkpoints. */
    public synchronized int poll(long userFloor, long animalFloor, int limit) {
        if (userFloor < 0 || animalFloor < 0 || limit < 1 || limit > 100) throw invalid();
        if (interrupted()) return 0;
        List<Envelope> pending = pending(userFloor, limit);
        int completed = 0;
        int unresolved = 0;
        try {
            for (Envelope row : Objects.requireNonNull(pending)) {
                if (interrupted()) return completed;
                try {
                    // A conflicting receipt cannot authorize a module call, including on a stale selection.
                    Boolean already = transactions.execute(status -> received(row.outboxEventId(), DELIVERED));
                    if (Boolean.TRUE.equals(already)) continue;
                    FavoriteEvent event = favorite(row);
                    boolean applied = apply(event);
                    if (applied && !interrupted()) {
                        transactions.executeWithoutResult(status -> {
                            List<Envelope> current = jdbc.query(ENVELOPE, JdbcFavoriteEventDelivery::envelope, row.outboxId());
                            if (current.size() != 1 || !row.equals(current.getFirst())) throw invalid();
                            claim(row.outboxEventId(), DELIVERED);
                        });
                        completed++;
                    } else if (!interrupted()) unresolved++;
                } catch (RuntimeException failure) {
                    // Source rows remain pending. The single poll summary contains no event or failure details.
                    unresolved++;
                }
            }
            if (interrupted()) return completed;
            List<CompensationEnvelope> compensations = animals.pendingCompensations(animalFloor, limit);
            for (CompensationEnvelope row : Objects.requireNonNull(compensations)) {
                if (interrupted()) return completed;
                try {
                    Compensation event = compensation(row);
                    if (compensate(row, event)) completed++;
                    else if (!interrupted()) unresolved++;
                } catch (RuntimeException failure) {
                    // A failed final ACK replays the same compensation UUID and cannot repeat the pair deletion.
                    unresolved++;
                }
            }
            return completed;
        } finally {
            if (unresolved > 0) log.warn("Local favorite delivery has {} unresolved operations", unresolved);
        }
    }

    private List<Envelope> pending(long floor, int limit) {
        boolean begin = epochExhausted || currentFloor != floor;
        long cursor = begin ? floor : selectionCursor;
        // Stage the upper bound until the whole read transaction has returned successfully.
        long[] upper = {epochUpper};
        List<Envelope> rows = Objects.requireNonNull(transactions.execute(status -> {
            if (begin) upper[0] = Objects.requireNonNull(jdbc.queryForObject(EPOCH_UPPER, Long.class, floor, floor));
            return jdbc.query(PENDING, JdbcFavoriteEventDelivery::envelope, floor, upper[0], cursor, limit);
        }));
        currentFloor = floor;
        epochUpper = upper[0];
        selectionCursor = rows.isEmpty() ? cursor : rows.getLast().outboxId();
        epochExhausted = rows.isEmpty() || selectionCursor >= epochUpper;
        // This is only a bounded selection sweep. Durable receipts remain the completion authority.
        return rows;
    }

    private boolean compensate(CompensationEnvelope row, Compensation event) {
        for (int attempt = 0; attempt < 5; attempt++) {
            if (interrupted()) return false;
            try {
                transactions.executeWithoutResult(status -> {
                    if (!claim(event.eventId(), event.type())) return;
                    if (ROLLBACK_ADDED.equals(event.type())) {
                        jdbc.update("DELETE FROM pawbridge_user.favorites WHERE user_id=? AND animal_id=?", event.userId(), event.animalId());
                    }
                });
                if (interrupted()) return false;
                animals.acknowledgeCompensation(row);
                return true;
            } catch (RuntimeException failure) {
                if (interrupted()) return false;
                if (attempt < 4 && !pause()) return false;
            }
        }
        return false;
    }

    private boolean apply(FavoriteEvent event) {
        for (int attempt = 0; attempt < 4; attempt++) {
            if (interrupted()) return false;
            try {
                animals.applyFavorite(event);
                return true;
            } catch (RuntimeException unresolved) {
                if (interrupted()) return false;
                if (attempt < 3) {
                    if (!pause()) return false;
                }
            }
        }
        if (!ADDED.equals(event.eventType()) || interrupted()) return false;
        // This API must reobserve its durable receipt in a fresh Animal transaction before deciding recovery.
        // The caller never infers rollback from an exception or an absent network response.
        animals.recoverAdded(event);
        return true;
    }

    private FavoriteEvent favorite(Envelope row) {
        if (row == null || row.outboxId() <= 0 || !text(row.outboxEventId(), 36)
                || !"Favorite".equals(row.aggregateType()) || !"user.favorite.events".equals(row.topic())
                || !(ADDED.equals(row.eventType()) || REMOVED.equals(row.eventType()))) throw invalid();
        JsonNode body = body(row.payload());
        String id = string(body, "eventId", 50);
        long member = number(body, "userId"), animal = number(body, "animalId");
        if (!row.eventType().equals(string(body, "eventType", 50))
                || !Long.toString(member).equals(row.aggregateId()) || id.equals(row.outboxEventId())) throw invalid();
        return new FavoriteEvent(id, member, animal, row.eventType());
    }

    private Compensation compensation(CompensationEnvelope row) {
        if (row == null || row.outboxId() <= 0 || !text(row.outboxEventId(), 50)
                || !COMPENSATION.equals(row.eventType()) || !"user.compensation.events".equals(row.topic())) throw invalid();
        JsonNode body = body(row.payload());
        String id = string(body, "eventId", 36), original = string(body, "originalEventId", 50);
        String type = string(body, "compensationType", 50);
        long member = number(body, "userId"), animal = number(body, "animalId");
        if (!COMPENSATION.equals(string(body, "eventType", 50)) || !Long.toString(member).equals(row.aggregateId())
                || !(ROLLBACK_ADDED.equals(type) || ROLLBACK_REMOVED.equals(type))
                || id.equals(row.outboxEventId()) || original.equals(id) || original.equals(row.outboxEventId())) throw invalid();
        return new Compensation(id, original, type, member, animal);
    }

    private JsonNode body(String payload) {
        if (payload == null) throw invalid();
        try {
            JsonNode result = json.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(payload);
            if (result == null || !result.isObject()) throw invalid();
            return result;
        } catch (JsonProcessingException malformed) { throw invalid(); }
    }

    private static String string(JsonNode body, String key, int max) {
        JsonNode value = body.path(key);
        if (!value.isTextual() || !text(value.textValue(), max)) throw invalid();
        return value.textValue();
    }

    private static long number(JsonNode body, String key) {
        JsonNode value = body.path(key);
        if (!value.isIntegralNumber() || !value.canConvertToLong()) throw invalid();
        return value.longValue();
    }

    private boolean received(String id, String expected) {
        List<String> types = jdbc.query(RECEIPT, (row, index) -> row.getString("event_type"), id);
        if (types.isEmpty()) return false;
        if (types.size() != 1 || !expected.equals(types.getFirst())) throw invalid();
        return true;
    }

    private boolean claim(String id, String expected) {
        if (received(id, expected)) return false;
        int inserted = jdbc.update(INSERT_RECEIPT, id, expected, timestamp(LocalDateTime.now()));
        if (inserted == 1) return true;
        if (inserted != 0 || !received(id, expected)) throw invalid();
        return false;
    }

    private boolean interrupted() { return stopped || Thread.currentThread().isInterrupted(); }
    private boolean pause() { retryDelay.run(); return !interrupted(); }
    private static void sleepBeforeRetry() {
        try { Thread.sleep(1000); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
    }
    private static boolean text(String value, int max) { return value != null && !value.isBlank() && value.length() <= max; }
    private static IllegalStateException invalid() { return new IllegalStateException("Local favorite delivery is unresolved"); }
    private static Envelope envelope(ResultSet row, int index) throws SQLException {
        return new Envelope(row.getLong("outbox_event_id"), row.getString("event_id"), row.getString("aggregate_type"),
                row.getString("aggregate_id"), row.getString("event_type"), row.getString("topic"), row.getString("payload"));
    }
    private static SqlValue timestamp(LocalDateTime value) {
        return new SqlValue() {
            public void setValue(java.sql.PreparedStatement statement, int index) throws SQLException {
                statement.setTimestamp(index, Timestamp.valueOf(value), Calendar.getInstance(TimeZone.getTimeZone("UTC")));
            }
            public void cleanup() { }
        };
    }
    private record Envelope(long outboxId, String outboxEventId, String aggregateType, String aggregateId,
            String eventType, String topic, String payload) { }
    private record Compensation(String eventId, String originalEventId, String type, long userId, long animalId) { }
}
