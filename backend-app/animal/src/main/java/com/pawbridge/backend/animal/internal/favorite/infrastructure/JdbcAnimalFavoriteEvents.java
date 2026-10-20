package com.pawbridge.backend.animal.internal.favorite.infrastructure;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pawbridge.backend.animal.api.AnimalFavoriteEvents;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TimeZone;
import java.util.UUID;
import java.util.function.Supplier;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.SqlValue;
import org.springframework.transaction.support.TransactionOperations;

public final class JdbcAnimalFavoriteEvents implements AnimalFavoriteEvents {
    private static final Log LOG = LogFactory.getLog(JdbcAnimalFavoriteEvents.class);
    static final String ADDED = "FAVORITE_ADDED";
    static final String REMOVED = "FAVORITE_REMOVED";
    static final String COMPENSATED = "FAVORITE_ADDED_COMPENSATED";
    static final String DELIVERED = "LOCAL_COMPENSATION_DELIVERED";
    static final String COMPENSATION = "FAVORITE_COMPENSATION_REQUIRED";
    static final String TOPIC = "user.compensation.events";
    static final String RECEIPT = "SELECT event_type FROM pawbridge_animal.processed_events WHERE event_id=?";
    static final String INSERT_RECEIPT = "INSERT INTO pawbridge_animal.processed_events(event_id,event_type,processed_at) VALUES(?,?,?) ON CONFLICT(event_id) DO NOTHING";
    static final String INCREMENT = "UPDATE pawbridge_animal.animals SET favorite_count=favorite_count+1,updated_at=? WHERE id=?";
    static final String DECREMENT = "UPDATE pawbridge_animal.animals SET favorite_count=CASE WHEN favorite_count>0 THEN favorite_count-1 ELSE favorite_count END,updated_at=CASE WHEN favorite_count>0 THEN ? ELSE updated_at END WHERE id=?";
    static final String INSERT_OUTBOX = "INSERT INTO pawbridge_animal.outbox_events(event_id,aggregate_type,aggregate_id,event_type,topic,payload,created_at) VALUES(?,'FavoriteCompensation',?,?,?,?,?)";
    private static final String COLUMNS = "o.id,o.event_id,o.aggregate_id,o.event_type,o.topic,o.payload";
    static final String EPOCH_UPPER = "SELECT COALESCE(MAX(id),?) FROM pawbridge_animal.outbox_events WHERE id>? AND aggregate_type='FavoriteCompensation' AND topic='user.compensation.events'";
    static final String PENDING = """
            SELECT %s FROM pawbridge_animal.outbox_events o
            WHERE o.id>? AND o.id<=? AND o.id>? AND o.aggregate_type='FavoriteCompensation' AND o.topic='user.compensation.events'
              AND NOT EXISTS (SELECT 1 FROM pawbridge_animal.processed_events p
                  WHERE p.event_id=o.event_id AND p.event_type='LOCAL_COMPENSATION_DELIVERED')
              AND NOT EXISTS (SELECT 1 FROM pawbridge_animal.outbox_events earlier
                  WHERE earlier.id>? AND earlier.id<=? AND earlier.id<o.id AND earlier.aggregate_id=o.aggregate_id
                    AND earlier.aggregate_type='FavoriteCompensation' AND earlier.topic='user.compensation.events'
                    AND NOT EXISTS (SELECT 1 FROM pawbridge_animal.processed_events done
                        WHERE done.event_id=earlier.event_id AND done.event_type='LOCAL_COMPENSATION_DELIVERED'))
            ORDER BY o.id LIMIT ?
            """.formatted(COLUMNS);
    static final String ENVELOPE = "SELECT " + COLUMNS + " FROM pawbridge_animal.outbox_events o WHERE o.id=? AND o.aggregate_type='FavoriteCompensation'";
    private static final RowMapper<CompensationEnvelope> ENVELOPES = (rows, index) -> new CompensationEnvelope(
            rows.getLong("id"), rows.getString("event_id"), rows.getString("aggregate_id"),
            rows.getString("event_type"), rows.getString("topic"), rows.getString("payload"));

    private final JdbcOperations jdbc;
    private final TransactionOperations transactions;
    private final Supplier<UUID> ids;
    private final Supplier<LocalDateTime> now;
    private final ObjectMapper json = new ObjectMapper();
    // Selection fairness only: receipts remain the sole durable completion authority.
    private long currentFloor = -1;
    private long selectionCursor;
    private long epochUpper;
    private boolean exhausted = true;

    private record PendingBatch(List<CompensationEnvelope> valid, long upper, long lastId, int invalid) { }

    public JdbcAnimalFavoriteEvents(JdbcOperations jdbc, TransactionOperations transactions) {
        this(jdbc, transactions, UUID::randomUUID, LocalDateTime::now);
    }

    public JdbcAnimalFavoriteEvents(JdbcOperations jdbc, TransactionOperations transactions,
            Supplier<UUID> ids, Supplier<LocalDateTime> now) {
        this.jdbc = Objects.requireNonNull(jdbc);
        this.transactions = Objects.requireNonNull(transactions);
        this.ids = Objects.requireNonNull(ids);
        this.now = Objects.requireNonNull(now);
    }

    @Override
    public void applyFavorite(FavoriteEvent event) {
        transactions.executeWithoutResult(status -> {
            validateFavorite(event);
            if (alreadyReceived(event)) return;
            // The receipt is invisible until commit; any failed mutation rolls it back too.
            if (!claim(event.eventId(), event.eventType())) {
                requireReceived(event);
                return;
            }
            int affected = jdbc.update(ADDED.equals(event.eventType()) ? INCREMENT : DECREMENT,
                    timestamp(now.get()), event.animalId());
            if (affected != 1) throw invalid("Favorite animal is unavailable");
        });
    }

    @Override
    public void recoverAdded(FavoriteEvent event) {
        transactions.executeWithoutResult(status -> {
            validateFavorite(event);
            if (!ADDED.equals(event.eventType())) throw invalid("Only added favorites support recovery");
            // Do not classify a prior commit from the caller's exception. Reobserve here; a racing
            // committed apply wins the unique receipt claim, while an unreadable outcome propagates.
            if (alreadyReceived(event)) return;
            if (!claim(event.eventId(), COMPENSATED)) {
                requireReceived(event);
                return;
            }
            String compensationId = ids.get().toString();
            String outboxId = ids.get().toString();
            if (compensationId.equals(outboxId) || compensationId.equals(event.eventId()) || outboxId.equals(event.eventId())
                    || receipt(compensationId) != null || receipt(outboxId) != null) {
                throw invalid("Favorite event identity collision");
            }
            LocalDateTime created = now.get();
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("eventId", compensationId);
            payload.put("eventType", COMPENSATION);
            payload.put("timestamp", created.toString());
            payload.put("originalEventId", event.eventId());
            payload.put("userId", event.userId());
            payload.put("animalId", event.animalId());
            payload.put("compensationType", "ROLLBACK_FAVORITE_ADDED");
            payload.put("failureReason", "animal-service failed to increment favoriteCount after 3 retries");
            final String body;
            try { body = json.writeValueAsString(payload); }
            catch (JsonProcessingException failure) { throw invalid("Favorite compensation serialization failed"); }
            if (jdbc.update(INSERT_OUTBOX, outboxId, Long.toString(event.userId()), COMPENSATION, TOPIC, body, timestamp(created)) != 1) {
                throw invalid("Favorite compensation persistence failed");
            }
        });
    }

    @Override
    public synchronized List<CompensationEnvelope> pendingCompensations(long afterFloor, int limit) {
        if (afterFloor < 0 || limit < 1 || limit > 100) throw invalid("Favorite compensation batch is invalid");
        boolean beginEpoch = exhausted || currentFloor != afterFloor;
        long cursor = beginEpoch ? afterFloor : selectionCursor;
        PendingBatch batch = transactions.execute(status -> {
            Long captured = beginEpoch ? jdbc.queryForObject(EPOCH_UPPER, Long.class, afterFloor, afterFloor) : epochUpper;
            if (captured == null || captured < afterFloor) throw invalid("Favorite compensation epoch is invalid");
            long upper = captured;
            List<CompensationEnvelope> rows = jdbc.query(PENDING, ENVELOPES, afterFloor, upper, cursor, afterFloor, upper, limit);
            List<CompensationEnvelope> valid = new ArrayList<>();
            int invalidCount = 0;
            for (CompensationEnvelope envelope : rows) {
                // Catch format failures only. Receipt/SQL failures must abort the read and retain the cursor.
                try { validateEnvelope(envelope); }
                catch (IllegalStateException malformed) { invalidCount++; continue; }
                String existing = receipt(envelope.outboxEventId());
                if (existing != null) {
                    if (!DELIVERED.equals(existing)) invalidCount++;
                    continue;
                }
                valid.add(envelope);
            }
            return new PendingBatch(List.copyOf(valid), upper, rows.isEmpty() ? upper : rows.getLast().outboxId(), invalidCount);
        });
        // Commit/read failure (including unknown commit response) leaves all selection state untouched.
        currentFloor = afterFloor;
        epochUpper = batch.upper();
        selectionCursor = batch.lastId();
        exhausted = selectionCursor >= epochUpper;
        if (batch.invalid() > 0) LOG.warn("Favorite compensation pending invalid count=" + batch.invalid());
        return batch.valid();
    }

    @Override
    public void acknowledgeCompensation(CompensationEnvelope envelope) {
        transactions.executeWithoutResult(status -> {
            validateEnvelope(envelope);
            List<CompensationEnvelope> rows = jdbc.query(ENVELOPE, ENVELOPES, envelope.outboxId());
            if (rows.size() != 1 || !rows.getFirst().equals(envelope)) throw invalid("Favorite compensation envelope changed");
            String existing = receipt(envelope.outboxEventId());
            if (existing != null) {
                if (!DELIVERED.equals(existing)) throw invalid("Favorite event identity collision");
                return;
            }
            if (!claim(envelope.outboxEventId(), DELIVERED) && !DELIVERED.equals(receipt(envelope.outboxEventId()))) {
                throw invalid("Favorite event identity collision");
            }
        });
    }

    private boolean alreadyReceived(FavoriteEvent event) {
        String existing = receipt(event.eventId());
        if (existing == null) return false;
        if (event.eventType().equals(existing) || ADDED.equals(event.eventType()) && COMPENSATED.equals(existing)) return true;
        throw invalid("Favorite event identity collision");
    }

    private void requireReceived(FavoriteEvent event) {
        if (!alreadyReceived(event)) throw invalid("Favorite event receipt is unresolved");
    }

    private String receipt(String id) {
        List<String> rows = jdbc.query(RECEIPT, (row, index) -> row.getString("event_type"), id);
        if (rows.size() > 1) throw invalid("Favorite event receipt is invalid");
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private boolean claim(String id, String type) {
        int affected = jdbc.update(INSERT_RECEIPT, id, type, timestamp(now.get()));
        if (affected < 0 || affected > 1) throw invalid("Favorite event receipt persistence failed");
        return affected == 1;
    }

    private void validateFavorite(FavoriteEvent event) {
        if (event == null || !text(event.eventId(), 50) || !(ADDED.equals(event.eventType()) || REMOVED.equals(event.eventType()))) {
            throw invalid("Favorite event is invalid");
        }
    }

    private void validateEnvelope(CompensationEnvelope envelope) {
        if (envelope == null || envelope.outboxId() <= 0 || !text(envelope.outboxEventId(), 50)
                || !COMPENSATION.equals(envelope.eventType()) || !TOPIC.equals(envelope.topic()) || envelope.payload() == null) {
            throw invalid("Favorite compensation envelope is invalid");
        }
        final JsonNode body;
        try { body = json.readTree(envelope.payload()); }
        catch (JsonProcessingException failure) { throw invalid("Favorite compensation payload is invalid"); }
        if (body == null || !body.isObject() || !COMPENSATION.equals(body.path("eventType").asText())
                || !body.path("eventId").isTextual() || !text(body.path("eventId").textValue(), 36)
                || !body.path("originalEventId").isTextual() || !text(body.path("originalEventId").textValue(), 50)
                || !body.path("userId").isIntegralNumber() || !body.path("userId").canConvertToLong()
                || !body.path("animalId").isIntegralNumber() || !body.path("animalId").canConvertToLong()
                || !Long.toString(body.path("userId").longValue()).equals(envelope.aggregateId())
                || !("ROLLBACK_FAVORITE_ADDED".equals(body.path("compensationType").asText())
                    || "ROLLBACK_FAVORITE_REMOVED".equals(body.path("compensationType").asText()))
                || envelope.outboxEventId().equals(body.path("eventId").textValue())
                || envelope.outboxEventId().equals(body.path("originalEventId").textValue())
                || body.path("eventId").textValue().equals(body.path("originalEventId").textValue())) {
            throw invalid("Favorite compensation payload is invalid");
        }
    }

    private static boolean text(String value, int max) { return value != null && !value.isBlank() && value.length() <= max; }
    private static IllegalStateException invalid(String message) { return new IllegalStateException(message); }

    private static SqlValue timestamp(LocalDateTime value) {
        return new SqlValue() {
            public void setValue(PreparedStatement statement, int index) throws SQLException {
                statement.setTimestamp(index, Timestamp.valueOf(value), Calendar.getInstance(TimeZone.getTimeZone("UTC")));
            }
            public void cleanup() { }
        };
    }
}
