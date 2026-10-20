package com.pawbridge.backend.animal.internal.favorite.infrastructure;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.slf4j.LoggerFactory;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pawbridge.backend.animal.api.AnimalFavoriteEvents.CompensationEnvelope;
import com.pawbridge.backend.animal.api.AnimalFavoriteEvents.FavoriteEvent;
import java.sql.ResultSet;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Calendar;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.SqlValue;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import static com.pawbridge.backend.animal.internal.favorite.infrastructure.JdbcAnimalFavoriteEvents.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SuppressWarnings({"unchecked", "rawtypes"})
class JdbcAnimalFavoriteEventsTest {
    private static final String SOURCE = "93000000-0000-0000-0000-000000000001";
    private static final UUID COMP = UUID.fromString("93000000-0000-0000-0000-000000000002");
    private static final UUID OUT = UUID.fromString("93000000-0000-0000-0000-000000000003");
    private static final LocalDateTime NOW = LocalDateTime.of(2032, 1, 2, 3, 4, 5, 123456000);
    private final JdbcOperations jdbc = mock(JdbcOperations.class);
    private final PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
    private final Map<String, String> receipts = new HashMap<>();
    private final ArrayDeque<UUID> ids = new ArrayDeque<>(List.of(COMP, OUT));
    private final AtomicInteger uuidCalls = new AtomicInteger();
    private final JdbcAnimalFavoriteEvents events = new JdbcAnimalFavoriteEvents(jdbc, new TransactionTemplate(manager),
            () -> { uuidCalls.incrementAndGet(); return ids.removeFirst(); }, () -> NOW);

    @BeforeEach
    void givenTransactionsAndReceiptReplies() {
        when(manager.getTransaction(any())).thenAnswer(call -> new SimpleTransactionStatus());
        when(jdbc.queryForObject(eq(EPOCH_UPPER), eq(Long.class), anyLong(), anyLong())).thenReturn(31L);
        when(jdbc.query(eq(RECEIPT), any(RowMapper.class), anyString())).thenAnswer(call -> {
            String type = receipts.get(call.getArgument(2));
            return type == null ? List.of() : List.of(type);
        });
        when(jdbc.update(eq(INSERT_RECEIPT), anyString(), anyString(), any(SqlValue.class))).thenAnswer(call -> {
            return receipts.putIfAbsent(call.getArgument(1), call.getArgument(2)) == null ? 1 : 0;
        });
        when(jdbc.update(eq(INCREMENT), any(SqlValue.class), anyLong())).thenReturn(1);
        when(jdbc.update(eq(DECREMENT), any(SqlValue.class), anyLong())).thenReturn(1);
        when(jdbc.update(eq(INSERT_OUTBOX), anyString(), anyString(), anyString(), anyString(), anyString(), any(SqlValue.class))).thenReturn(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {ADDED, REMOVED})
    void givenFreshPayload__whenApplying__thenClaimReceiptBeforeAtomicMutationAndReturnOnlyAfterCommit(String type) throws Exception {
        events.applyFavorite(favorite(type));
        var order = inOrder(jdbc, manager);
        order.verify(jdbc).update(eq(INSERT_RECEIPT), eq(SOURCE), eq(type), any(SqlValue.class));
        var audit = ArgumentCaptor.forClass(SqlValue.class);
        order.verify(jdbc).update(eq(type.equals(ADDED) ? INCREMENT : DECREMENT), audit.capture(), eq(22L));
        order.verify(manager).commit(any());
        assertThat(DECREMENT).contains("CASE WHEN favorite_count>0 THEN favorite_count-1 ELSE favorite_count END");
        assertThat(INCREMENT).contains("favorite_count=favorite_count+1,updated_at=?");
        assertThat(DECREMENT).contains("updated_at=CASE WHEN favorite_count>0 THEN ? ELSE updated_at END");
        PreparedStatement statement = mock(PreparedStatement.class);
        audit.getValue().setValue(statement, 1);
        var calendar = ArgumentCaptor.forClass(Calendar.class);
        verify(statement).setTimestamp(eq(1), eq(Timestamp.valueOf(NOW)), calendar.capture());
        assertThat(calendar.getValue().getTimeZone().getID()).isEqualTo("UTC");
        assertThat(uuidCalls).hasValue(0);
    }

    @ParameterizedTest
    @CsvSource({"FAVORITE_ADDED,FAVORITE_ADDED", "FAVORITE_ADDED,FAVORITE_ADDED_COMPENSATED", "FAVORITE_REMOVED,FAVORITE_REMOVED"})
    void givenCompletedPayload__whenApplyingAgain__thenDoNotMutateOrGenerateIds(String requested, String stored) {
        receipts.put(SOURCE, stored);
        events.applyFavorite(favorite(requested));
        verify(jdbc, never()).update(anyString(), any(Object[].class));
        assertThat(uuidCalls).hasValue(0);
    }

    @ParameterizedTest
    @ValueSource(strings = {DELIVERED, REMOVED})
    void givenReceiverIdCollidesWithAnotherPurpose__whenApplying__thenFailClosedWithoutMutation(String stored) {
        receipts.put(SOURCE, stored);
        assertThatThrownBy(() -> events.applyFavorite(favorite(ADDED))).isInstanceOf(IllegalStateException.class);
        verify(jdbc, never()).update(anyString(), any(Object[].class));
        verify(manager).rollback(any());
    }

    @Test
    void givenConcurrentReceiptWinner__whenClaimLoses__thenReobserveBeforeSkippingTheCounter() {
        when(jdbc.update(eq(INSERT_RECEIPT), eq(SOURCE), eq(ADDED), any(SqlValue.class))).thenAnswer(call -> {
            receipts.put(SOURCE, ADDED);
            return 0;
        });
        events.applyFavorite(favorite(ADDED));
        verify(jdbc, never()).update(eq(INCREMENT), any(SqlValue.class), anyLong());
        verify(jdbc, times(2)).query(eq(RECEIPT), any(RowMapper.class), eq(SOURCE));
    }

    @Test
    void givenMissingAnimal__whenApplying__thenPropagateAndRequestRollbackOfTheUncommittedReceipt() {
        when(jdbc.update(eq(INCREMENT), any(SqlValue.class), eq(22L))).thenReturn(0);
        assertThatThrownBy(() -> events.applyFavorite(favorite(ADDED))).isInstanceOf(IllegalStateException.class);
        verify(manager).rollback(any());
        verify(manager, never()).commit(any());
    }

    @Test
    void givenExhaustedAddedFailure__whenRecovering__thenKeepOriginalJsonAndDistinctPayloadAndOutboxIds() throws Exception {
        events.recoverAdded(favorite(ADDED));
        var body = ArgumentCaptor.forClass(String.class);
        var order = inOrder(jdbc, manager);
        order.verify(jdbc).update(eq(INSERT_RECEIPT), eq(SOURCE), eq(COMPENSATED), any(SqlValue.class));
        order.verify(jdbc).update(eq(INSERT_OUTBOX), eq(OUT.toString()), eq("11"), eq(COMPENSATION), eq(TOPIC), body.capture(), any(SqlValue.class));
        order.verify(manager).commit(any());
        JsonNode payload = new ObjectMapper().readTree(body.getValue());
        assertThat(payload.size()).isEqualTo(8);
        assertThat(payload.path("eventId").asText()).isEqualTo(COMP.toString());
        assertThat(payload.path("originalEventId").asText()).isEqualTo(SOURCE);
        assertThat(payload.path("eventType").asText()).isEqualTo(COMPENSATION);
        assertThat(payload.path("compensationType").asText()).isEqualTo("ROLLBACK_FAVORITE_ADDED");
        assertThat(payload.path("userId").asLong()).isEqualTo(11);
        assertThat(payload.path("animalId").asLong()).isEqualTo(22);
        assertThat(payload.path("timestamp").asText()).isEqualTo(NOW.toString());
        assertThat(payload.path("failureReason").asText()).isEqualTo("animal-service failed to increment favoriteCount after 3 retries");
        assertThat(uuidCalls).hasValue(2);
    }

    @ParameterizedTest
    @ValueSource(strings = {ADDED, COMPENSATED})
    void givenAddedAlreadyResolved__whenRecoveringAgain__thenCreateNoAdditionalCompensation(String stored) {
        receipts.put(SOURCE, stored);
        events.recoverAdded(favorite(ADDED));
        assertThat(uuidCalls).hasValue(0);
        verify(jdbc, never()).update(anyString(), any(Object[].class));
    }

    @Test
    void givenRemovedFailure__whenAskedToRecover__thenKeepItUnresolvedWithoutAnyRestorationOrOutbox() {
        assertThatThrownBy(() -> events.recoverAdded(favorite(REMOVED))).isInstanceOf(IllegalStateException.class);
        verify(jdbc, never()).update(anyString(), any(Object[].class));
        assertThat(uuidCalls).hasValue(0);
    }

    @Test
    void givenCompensationOutboxFailure__whenRecovering__thenPropagateTheSameFailureAndRequestRollback() {
        var failure = new DataAccessResourceFailureException("synthetic-private-db-marker");
        when(jdbc.update(eq(INSERT_OUTBOX), anyString(), anyString(), anyString(), anyString(), anyString(), any(SqlValue.class))).thenThrow(failure);
        assertThatThrownBy(() -> events.recoverAdded(favorite(ADDED))).isSameAs(failure);
        verify(manager).rollback(any());
        verify(manager, never()).commit(any());
    }

    @Test
    void givenGeneratedIdsCollide__whenRecovering__thenFailBeforePublishingAnAmbiguousEnvelope() {
        ids.clear(); ids.add(COMP); ids.add(COMP);
        assertThatThrownBy(() -> events.recoverAdded(favorite(ADDED))).isInstanceOf(IllegalStateException.class);
        verify(jdbc, never()).update(eq(INSERT_OUTBOX), anyString(), anyString(), anyString(), anyString(), anyString(), any(SqlValue.class));
        verify(manager).rollback(any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"ROLLBACK_FAVORITE_ADDED", "ROLLBACK_FAVORITE_REMOVED"})
    void givenPendingCompensation__whenPolling__thenReadOnlyOwnFixedFloorAndReceiptsWithPerAggregatePredecessor(String type) throws Exception {
        CompensationEnvelope expected = envelope(type);
        when(jdbc.query(eq(PENDING), any(RowMapper.class), eq(10L), eq(31L), eq(10L), eq(10L), eq(31L), eq(25))).thenAnswer(call -> {
            ResultSet row = mock(ResultSet.class);
            when(row.getLong("id")).thenReturn(expected.outboxId());
            when(row.getString("event_id")).thenReturn(expected.outboxEventId());
            when(row.getString("aggregate_id")).thenReturn(expected.aggregateId());
            when(row.getString("event_type")).thenReturn(expected.eventType());
            when(row.getString("topic")).thenReturn(expected.topic());
            when(row.getString("payload")).thenReturn(expected.payload());
            return List.of(((RowMapper<CompensationEnvelope>) call.getArgument(1)).mapRow(row, 0));
        });
        assertThat(events.pendingCompensations(10, 25)).containsExactly(expected);
        assertThat(PENDING).contains("o.id>? AND o.id<=? AND o.id>?", "p.event_type='LOCAL_COMPENSATION_DELIVERED'", "earlier.id>? AND earlier.id<=? AND earlier.id<o.id", "earlier.aggregate_id=o.aggregate_id", "ORDER BY o.id LIMIT ?")
                .doesNotContain("pawbridge_user", "DELETE", "UPDATE");
        verify(jdbc, never()).update(anyString(), any(Object[].class));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 101})
    void givenUnboundedBatch__whenPolling__thenRejectBeforeOpeningATransaction(int limit) {
        assertThatThrownBy(() -> events.pendingCompensations(0, limit)).isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(manager);
    }

    @Test
    void givenUnknownCompensation__whenPolling__thenLeaveItUnacknowledged() {
        when(jdbc.query(eq(PENDING), any(RowMapper.class), eq(0L), eq(31L), eq(0L), eq(0L), eq(31L), eq(1))).thenReturn(List.of(envelope("UNKNOWN")));
        assertThat(events.pendingCompensations(0, 1)).isEmpty();
        verify(jdbc, never()).update(anyString(), any(Object[].class));
    }

    @Test
    void givenMalformedPrivatePayload__whenPolling__thenKeepItPendingAndWarnOnlyItsCount() {
        var envelope = new CompensationEnvelope(31, OUT.toString(), "11", COMPENSATION, TOPIC,
                "{\"eventId\":\"synthetic-private-body-marker\"");
        when(jdbc.query(eq(PENDING), any(RowMapper.class), eq(0L), eq(31L), eq(0L), eq(0L), eq(31L), eq(1))).thenReturn(List.of(envelope));
        List<ILoggingEvent> warnings = warnings(() -> assertThat(events.pendingCompensations(0, 1)).isEmpty());
        assertThat(warnings).hasSize(1);
        assertThat(warnings.getFirst().getFormattedMessage()).isEqualTo("Favorite compensation pending invalid count=1");
        assertThat(warnings.getFirst().getThrowableProxy()).isNull();
        verify(jdbc, never()).update(anyString(), any(Object[].class));
    }

    @Test
    void givenSenderIdIsAlreadyAReceiverReceipt__whenPolling__thenLeaveOnlyThatRowPendingBeforeUserCallback() {
        receipts.put(OUT.toString(), ADDED);
        when(jdbc.query(eq(PENDING), any(RowMapper.class), eq(0L), eq(31L), eq(0L), eq(0L), eq(31L), eq(1))).thenReturn(List.of(envelope("ROLLBACK_FAVORITE_ADDED")));
        assertThat(events.pendingCompensations(0, 1)).isEmpty();
        verify(jdbc, never()).update(anyString(), any(Object[].class));
    }

    @Test
    void givenPoisonAndValidDifferentAggregates__whenPollingTogether__thenReturnTheValidRowAndOnlyCountThePoison() {
        CompensationEnvelope good = envelopeAt(32);
        CompensationEnvelope bad = new CompensationEnvelope(31, OUT.toString(), "11", COMPENSATION, TOPIC, "private-poison-body");
        when(jdbc.queryForObject(eq(EPOCH_UPPER), eq(Long.class), eq(0L), eq(0L))).thenReturn(32L);
        when(jdbc.query(eq(PENDING), any(RowMapper.class), eq(0L), eq(32L), eq(0L), eq(0L), eq(32L), eq(2)))
                .thenReturn(List.of(bad, good));
        List<ILoggingEvent> warnings = warnings(() -> assertThat(events.pendingCompensations(0, 2)).containsExactly(good));
        assertThat(warnings).hasSize(1);
        assertThat(warnings.getFirst().getFormattedMessage()).isEqualTo("Favorite compensation pending invalid count=1");
        assertThat(warnings.getFirst().getThrowableProxy()).isNull();
        verify(jdbc, never()).update(anyString(), any(Object[].class));
    }

    @Test
    void givenContinuousTailAndLateLowerCommit__whenPollingFiniteEpochs__thenAdvanceWithoutAcknowledgementsAndWrapAtTheFixedUpper() {
        when(jdbc.queryForObject(eq(EPOCH_UPPER), eq(Long.class), eq(0L), eq(0L))).thenReturn(32L, 34L);
        when(jdbc.query(eq(PENDING), any(RowMapper.class), anyLong(), anyLong(), anyLong(), anyLong(), anyLong(), eq(1)))
                .thenReturn(List.of(envelopeAt(31)), List.of(envelopeAt(32)), List.of(envelopeAt(30)),
                        List.of(envelopeAt(33)), List.of(envelopeAt(34)));
        for (long id : new long[] {31, 32, 30, 33, 34}) {
            assertThat(events.pendingCompensations(0, 1)).containsExactly(envelopeAt(id));
        }
        var order = inOrder(jdbc);
        order.verify(jdbc).queryForObject(EPOCH_UPPER, Long.class, 0L, 0L);
        order.verify(jdbc).query(eq(PENDING), any(RowMapper.class), eq(0L), eq(32L), eq(0L), eq(0L), eq(32L), eq(1));
        order.verify(jdbc).query(eq(PENDING), any(RowMapper.class), eq(0L), eq(32L), eq(31L), eq(0L), eq(32L), eq(1));
        order.verify(jdbc).queryForObject(EPOCH_UPPER, Long.class, 0L, 0L);
        order.verify(jdbc).query(eq(PENDING), any(RowMapper.class), eq(0L), eq(34L), eq(0L), eq(0L), eq(34L), eq(1));
        order.verify(jdbc).query(eq(PENDING), any(RowMapper.class), eq(0L), eq(34L), eq(30L), eq(0L), eq(34L), eq(1));
        order.verify(jdbc).query(eq(PENDING), any(RowMapper.class), eq(0L), eq(34L), eq(33L), eq(0L), eq(34L), eq(1));
        verify(jdbc, never()).update(anyString(), any(Object[].class));
    }

    @ParameterizedTest
    @ValueSource(strings = {"receipt", "commit"})
    void givenReadOrCommitFailureInsideAnEpoch__whenRetrying__thenPropagateAndRetainTheLastSuccessfulSelectionCursor(String phase) {
        when(jdbc.queryForObject(eq(EPOCH_UPPER), eq(Long.class), eq(0L), eq(0L))).thenReturn(33L);
        when(jdbc.query(eq(PENDING), any(RowMapper.class), anyLong(), anyLong(), anyLong(), anyLong(), anyLong(), eq(1)))
                .thenReturn(List.of(envelopeAt(31)), List.of(envelopeAt(32)), List.of(envelopeAt(32)));
        assertThat(events.pendingCompensations(0, 1)).containsExactly(envelopeAt(31));
        RuntimeException failure = new DataAccessResourceFailureException("private-database-marker");
        if (phase.equals("receipt")) {
            when(jdbc.query(eq(RECEIPT), any(RowMapper.class), eq(envelopeAt(32).outboxEventId())))
                    .thenThrow(failure).thenReturn(List.of());
        } else {
            doThrow(failure).doNothing().when(manager).commit(any());
        }
        List<ILoggingEvent> warnings = warnings(() ->
                assertThatThrownBy(() -> events.pendingCompensations(0, 1)).isSameAs(failure));
        assertThat(warnings).isEmpty();
        assertThat(events.pendingCompensations(0, 1)).containsExactly(envelopeAt(32));
        verify(jdbc, times(2)).query(eq(PENDING), any(RowMapper.class), eq(0L), eq(33L), eq(31L), eq(0L), eq(33L), eq(1));
        verify(jdbc, times(1)).queryForObject(EPOCH_UPPER, Long.class, 0L, 0L);
    }

    @Test
    void givenCommittedUserCallbackAndExactStoredEnvelope__whenAcknowledgingTwice__thenWriteOneOwnDeliveryReceiptAndKeepOutbox() {
        CompensationEnvelope envelope = envelope("ROLLBACK_FAVORITE_ADDED");
        when(jdbc.query(eq(ENVELOPE), any(RowMapper.class), eq(31L))).thenReturn(List.of(envelope));
        events.acknowledgeCompensation(envelope);
        events.acknowledgeCompensation(envelope);
        verify(jdbc, times(1)).update(eq(INSERT_RECEIPT), eq(OUT.toString()), eq(DELIVERED), any(SqlValue.class));
        verify(jdbc, never()).update(contains("DELETE"), any(Object[].class));
    }

    @Test
    void givenEnvelopeDoesNotMatchItsOwnedRow__whenAcknowledging__thenDoNotCreateAReceipt() {
        CompensationEnvelope envelope = envelope("ROLLBACK_FAVORITE_ADDED");
        when(jdbc.query(eq(ENVELOPE), any(RowMapper.class), eq(31L))).thenReturn(List.of());
        assertThatThrownBy(() -> events.acknowledgeCompensation(envelope)).isInstanceOf(IllegalStateException.class);
        verify(jdbc, never()).update(anyString(), any(Object[].class));
    }

    @Test
    void givenSenderReceiptPurposeChangesDuringCallback__whenAcknowledging__thenDoNotOverwriteItOrClaimSuccess() {
        CompensationEnvelope envelope = envelope("ROLLBACK_FAVORITE_ADDED");
        when(jdbc.query(eq(ENVELOPE), any(RowMapper.class), eq(31L))).thenReturn(List.of(envelope));
        receipts.put(OUT.toString(), ADDED);
        assertThatThrownBy(() -> events.acknowledgeCompensation(envelope)).isInstanceOf(IllegalStateException.class);
        verify(jdbc, never()).update(anyString(), any(Object[].class));
        verify(manager).rollback(any());
    }

    @Test
    void givenCommitResponseFailure__whenApplying__thenDoNotConvertItIntoBusinessSuccessOrRecovery() {
        var failure = new TransactionSystemException("synthetic-commit-outcome-unknown");
        doThrow(failure).when(manager).commit(any());
        assertThatThrownBy(() -> events.applyFavorite(favorite(ADDED))).isSameAs(failure);
        verify(jdbc, never()).update(eq(INSERT_OUTBOX), anyString(), anyString(), anyString(), anyString(), anyString(), any(SqlValue.class));
    }

    private static List<ILoggingEvent> warnings(Runnable action) {
        Logger logger = (Logger) LoggerFactory.getLogger(JdbcAnimalFavoriteEvents.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start(); logger.addAppender(appender);
        try { action.run(); return List.copyOf(appender.list); }
        finally { logger.detachAppender(appender); appender.stop(); }
    }

    private static CompensationEnvelope envelopeAt(long id) {
        String body = "{\"eventId\":\"" + new UUID(0, id * 3 + 1) + "\",\"eventType\":\"" + COMPENSATION
                + "\",\"originalEventId\":\"" + new UUID(0, id * 3 + 2) + "\",\"userId\":" + id
                + ",\"animalId\":22,\"compensationType\":\"ROLLBACK_FAVORITE_ADDED\"}";
        return new CompensationEnvelope(id, new UUID(0, id * 3).toString(), Long.toString(id), COMPENSATION, TOPIC, body);
    }

    private static FavoriteEvent favorite(String type) { return new FavoriteEvent(SOURCE, 11, 22, type); }

    private static CompensationEnvelope envelope(String type) {
        String body = "{\"eventId\":\"" + COMP + "\",\"eventType\":\"" + COMPENSATION
                + "\",\"originalEventId\":\"" + SOURCE + "\",\"userId\":11,\"animalId\":22,\"compensationType\":\"" + type + "\"}";
        return new CompensationEnvelope(31, OUT.toString(), "11", COMPENSATION, TOPIC, body);
    }
}
