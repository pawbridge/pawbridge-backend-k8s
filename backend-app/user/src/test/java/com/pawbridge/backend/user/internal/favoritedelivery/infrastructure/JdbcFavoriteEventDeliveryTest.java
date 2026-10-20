package com.pawbridge.backend.user.internal.favoritedelivery.infrastructure;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pawbridge.backend.animal.api.AnimalFavoriteEvents;
import com.pawbridge.backend.animal.api.AnimalFavoriteEvents.CompensationEnvelope;
import com.pawbridge.backend.animal.api.AnimalFavoriteEvents.FavoriteEvent;
import java.sql.ResultSet;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionOperations;

class JdbcFavoriteEventDeliveryTest {
    private static final String PAYLOAD = "11111111-1111-4111-8111-111111111111";
    private static final String OUTBOX = "22222222-2222-4222-8222-222222222222";
    private static final String COMPENSATION = "33333333-3333-4333-8333-333333333333";
    private static final String COMPENSATION_OUTBOX = "44444444-4444-4444-8444-444444444444";
    private static final String DELIVERED = "LOCAL_FAVORITE_DELIVERED";
    private final JdbcOperations jdbc = mock(JdbcOperations.class);
    private final AnimalFavoriteEvents animals = mock(AnimalFavoriteEvents.class);
    private final ObjectMapper json = new ObjectMapper();
    private final Map<String, String> receipts = new HashMap<>();
    private final List<Map<String, Object>> rows = new ArrayList<>();
    private final List<String> calls = new ArrayList<>();
    private final List<Object[]> pendingArguments = new ArrayList<>();
    private final List<String> pendingSql = new ArrayList<>();
    private final ArrayDeque<List<Map<String, Object>>> selections = new ArrayDeque<>();
    private final List<Object[]> upperArguments = new ArrayList<>();
    private final Tx tx = new Tx();
    private final AtomicInteger pauses = new AtomicInteger();
    private JdbcFavoriteEventDelivery delivery;
    private int deletes;
    private String raceType;
    private boolean failNextSelection;
    private Runnable duringSelection = () -> { };

    @BeforeEach @SuppressWarnings({"unchecked", "rawtypes"})
    void prepare() {
        delivery = new JdbcFavoriteEventDelivery(jdbc, tx, animals, json, () -> {
            assertFalse(tx.active); pauses.incrementAndGet(); calls.add("pause");
        });
        doAnswer(call -> {
            assertTrue(tx.active);
            String sql = call.getArgument(0);
            assertTrue(sql.contains("MAX(outbox_event_id)"));
            assertTrue(sql.contains("aggregate_type='Favorite' AND topic='user.favorite.events'"));
            Object[] args = (Object[]) call.getRawArguments()[2]; upperArguments.add(args.clone());
            long floor = (Long) args[0];
            return rows.stream().mapToLong(row -> (Long) row.get("outbox_event_id"))
                    .filter(id -> id > floor).max().orElse(floor);
        }).when(jdbc).queryForObject(anyString(), eq(Long.class), any(Object[].class));
        doAnswer(call -> {
            assertTrue(tx.active);
            String sql = call.getArgument(0);
            RowMapper mapper = call.getArgument(1);
            Object[] args = (Object[]) call.getRawArguments()[2];
            List<Map<String, Object>> found;
            if (sql.startsWith("WITH pending")) {
                pendingSql.add(sql); pendingArguments.add(args.clone());
                duringSelection.run();
                if (failNextSelection) { failNextSelection = false; throw new IllegalStateException("synthetic read unavailable"); }
                found = selections.isEmpty() ? List.copyOf(rows) : selections.removeFirst();
            } else if (sql.startsWith("SELECT outbox_event_id")) {
                found = rows.stream().filter(row -> row.get("outbox_event_id").equals(args[0])).toList();
            } else if (sql.startsWith("SELECT event_type")) {
                String value = receipts.get(args[0]);
                found = value == null ? List.of() : List.of(Map.of("event_type", value));
            } else throw new AssertionError(sql);
            var result = new ArrayList<>();
            for (Map<String, Object> values : found) {
                ResultSet rs = mock(ResultSet.class);
                when(rs.getLong(anyString())).thenAnswer(c -> ((Number) values.get(c.getArgument(0))).longValue());
                when(rs.getString(anyString())).thenAnswer(c -> (String) values.get(c.getArgument(0)));
                result.add(mapper.mapRow(rs, result.size()));
            }
            return result;
        }).when(jdbc).query(anyString(), any(RowMapper.class), any(Object[].class));
        doAnswer(call -> {
            assertTrue(tx.active);
            String sql = call.getArgument(0); Object[] args = (Object[]) call.getRawArguments()[1];
            if (sql.startsWith("INSERT INTO pawbridge_user.processed_events")) {
                if (raceType != null) { receipts.put((String) args[0], raceType); return 0; }
                return receipts.putIfAbsent((String) args[0], (String) args[1]) == null ? 1 : 0;
            }
            if (sql.equals("DELETE FROM pawbridge_user.favorites WHERE user_id=? AND animal_id=?")) {
                assertArrayEquals(new Object[]{7L, 90L}, args); deletes++; calls.add("delete"); return 0;
            }
            throw new AssertionError(sql);
        }).when(jdbc).update(anyString(), any(Object[].class));
        doAnswer(call -> { assertFalse(tx.active); calls.add("apply"); return null; }).when(animals).applyFavorite(any());
        doAnswer(call -> { assertFalse(tx.active); calls.add("recover"); return null; }).when(animals).recoverAdded(any());
        when(animals.pendingCompensations(anyLong(), anyInt())).thenAnswer(call -> { assertFalse(tx.active); return List.of(); });
        doAnswer(call -> { assertFalse(tx.active); calls.add("animal-ack"); return null; }).when(animals).acknowledgeCompensation(any());
    }

    @AfterEach void clearInterrupt() { Thread.interrupted(); }

    @ParameterizedTest @ValueSource(strings = {"FAVORITE_ADDED", "FAVORITE_REMOVED"})
    void given__one_source_and_a_stale_duplicate_selection__when__polling__then__call_actor_outside_user_transactions_and_ack_only_the_outbox_id(String type) throws Exception {
        rows.add(row(type));
        assertEquals(1, delivery.poll(0, 0, 5));
        assertEquals(0, delivery.poll(0, 0, 5));
        verify(animals).applyFavorite(new FavoriteEvent(PAYLOAD, 7, 90, type));
        verify(animals, never()).recoverAdded(any());
        assertEquals(Map.of(OUTBOX, DELIVERED), receipts);
        assertNotEquals(PAYLOAD, OUTBOX); assertEquals(0, deletes); assertEquals(0, pauses.get());
        assertTrue(calls.indexOf("apply") > calls.indexOf("commit"));
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void given__four_failed_added_attempts__when__recovery_commits_or_fails__then__delay_outside_tx_and_ack_only_confirmed_terminal_outcome(boolean recoveryFails) throws Exception {
        rows.add(row("FAVORITE_ADDED"));
        doAnswer(call -> { assertFalse(tx.active); calls.add("apply"); throw new IllegalStateException("synthetic failure"); }).when(animals).applyFavorite(any());
        if (recoveryFails) doThrow(new IllegalStateException("synthetic failure")).when(animals).recoverAdded(any());
        assertEquals(recoveryFails ? 0 : 1, delivery.poll(0, 0, 5));
        verify(animals, times(4)).applyFavorite(new FavoriteEvent(PAYLOAD, 7, 90, "FAVORITE_ADDED"));
        verify(animals).recoverAdded(new FavoriteEvent(PAYLOAD, 7, 90, "FAVORITE_ADDED"));
        assertEquals(3, pauses.get()); assertEquals(!recoveryFails, receipts.containsKey(OUTBOX));
        assertEquals(List.of("apply", "pause", "apply", "pause", "apply", "pause", "apply"), calls.stream().filter(v -> v.equals("apply") || v.equals("pause")).toList());
    }

    @Test void given__failed_removed_event__when__retry_budget_is_exhausted__then__keep_pending_without_recovery_or_favorite_restore() throws Exception {
        rows.add(row("FAVORITE_REMOVED")); doThrow(new IllegalStateException("synthetic failure")).when(animals).applyFavorite(any());
        assertEquals(0, delivery.poll(0, 0, 2)); verify(animals, times(4)).applyFavorite(any()); verify(animals, never()).recoverAdded(any());
        assertEquals(3, pauses.get()); assertTrue(receipts.isEmpty()); assertEquals(0, deletes);
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void given__animal_commit_then_uncertain_user_ack_commit__when__polling_again__then__reuse_payload_identity_or_observe_existing_ack(boolean persisted) throws Exception {
        rows.add(row("FAVORITE_ADDED")); tx.failReceiptCommit = true; tx.persistOnFailure = persisted;
        assertEquals(0, delivery.poll(0, 0, 2));
        assertEquals(persisted ? 0 : 1, delivery.poll(0, 0, 2));
        verify(animals, times(persisted ? 1 : 2)).applyFavorite(new FavoriteEvent(PAYLOAD, 7, 90, "FAVORITE_ADDED"));
        assertEquals(DELIVERED, receipts.get(OUTBOX));
    }

    @Test void given__changed_source_after_actor_commit__when__acknowledging__then__do_not_record_delivery_for_another_envelope() throws Exception {
        rows.add(row("FAVORITE_ADDED"));
        doAnswer(call -> { assertFalse(tx.active); rows.getFirst().put("payload", payload("FAVORITE_REMOVED")); return null; }).when(animals).applyFavorite(any());
        assertEquals(0, delivery.poll(0, 0, 2)); assertTrue(receipts.isEmpty());
    }

    static Stream<Arguments> malformedFavorites() {
        return Stream.of(Arguments.of("aggregate_type", "Other"), Arguments.of("topic", "wrong.topic"),
                Arguments.of("aggregate_id", "99"), Arguments.of("event_type", "UNKNOWN"),
                Arguments.of("event_id", PAYLOAD), Arguments.of("payload", "{"), Arguments.of("payload", "[]"),
                Arguments.of("payload", "{}"), Arguments.of("payload", "{\"eventId\":1}"),
                Arguments.of("payload", "{\"eventId\":\"p\",\"eventType\":\"FAVORITE_ADDED\",\"userId\":7.5,\"animalId\":90}"),
                Arguments.of("payload", "{\"eventId\":\"p\",\"eventType\":\"FAVORITE_ADDED\",\"userId\":7,\"animalId\":9223372036854775808}"),
                Arguments.of("payload", "{\"eventId\":\"p\",\"eventType\":\"FAVORITE_REMOVED\",\"userId\":7,\"animalId\":90}"));
    }
    @ParameterizedTest @MethodSource("malformedFavorites")
    void given__untraceable_or_mismatched_source__when__polling__then__leave_pending_without_actor_or_receipt(String field, Object value) throws Exception {
        var row = row("FAVORITE_ADDED"); row.put(field, value); rows.add(row);
        assertEquals(0, delivery.poll(0, 0, 2)); verify(animals, never()).applyFavorite(any()); verify(animals, never()).recoverAdded(any()); assertTrue(receipts.isEmpty());
    }

    @ParameterizedTest @CsvSource({"ROLLBACK_FAVORITE_ADDED,1", "ROLLBACK_FAVORITE_REMOVED,0"})
    void given__compensation__when__committing_user_receipt__then__delete_only_added_pair_allow_zero_rows_and_ack_animal_after_commit(String type, int expectedDeletes) throws Exception {
        var envelope = compensation(type); when(animals.pendingCompensations(10, 2)).thenAnswer(call -> { assertFalse(tx.active); return List.of(envelope); });
        assertEquals(1, delivery.poll(0, 10, 2)); assertEquals(expectedDeletes, deletes); assertEquals(type, receipts.get(COMPENSATION));
        verify(animals).acknowledgeCompensation(envelope); assertEquals("animal-ack", calls.getLast());
    }

    @Test void given__compensation_ack_response_loss__when__replaying__then__never_repeat_pair_delete() throws Exception {
        var envelope = compensation("ROLLBACK_FAVORITE_ADDED"); when(animals.pendingCompensations(anyLong(), anyInt())).thenReturn(List.of(envelope));
        var failure = new IllegalStateException("synthetic ack loss");
        doThrow(failure, failure, failure, failure).doNothing().when(animals).acknowledgeCompensation(envelope);
        assertEquals(1, delivery.poll(0, 0, 2));
        assertEquals(1, deletes); assertEquals(4, pauses.get()); verify(animals, times(5)).acknowledgeCompensation(envelope);
    }

    @Test void given__compensation_user_commit_failure__when__replaying__then__do_not_ack_animal_before_user_commit() throws Exception {
        var envelope = compensation("ROLLBACK_FAVORITE_ADDED"); when(animals.pendingCompensations(anyLong(), anyInt())).thenReturn(List.of(envelope));
        tx.failReceiptCommit = true; assertEquals(1, delivery.poll(0, 0, 2));
        assertEquals(1, pauses.get()); assertEquals(1, deletes); verify(animals).acknowledgeCompensation(envelope);
        assertEquals("ROLLBACK_FAVORITE_ADDED", receipts.get(COMPENSATION));
    }

    @Test void given__five_failed_compensation_acks__when__budget_exhausted__then__keep_pending_and_report_one_unresolved_row() throws Exception {
        var envelope = compensation("ROLLBACK_FAVORITE_ADDED"); when(animals.pendingCompensations(anyLong(), anyInt())).thenReturn(List.of(envelope));
        doThrow(new IllegalStateException("synthetic ack loss")).when(animals).acknowledgeCompensation(envelope);
        var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(JdbcFavoriteEventDelivery.class);
        var appender = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        appender.start(); logger.addAppender(appender);
        try {
            assertEquals(0, delivery.poll(0, 0, 2)); assertEquals(4, pauses.get()); assertEquals(1, deletes);
            verify(animals, times(5)).acknowledgeCompensation(envelope);
            assertEquals("ROLLBACK_FAVORITE_ADDED", receipts.get(COMPENSATION));
            assertEquals(1, appender.list.size()); assertEquals("Local favorite delivery has 1 unresolved operations", appender.list.getFirst().getFormattedMessage());
        } finally { logger.detachAppender(appender); appender.stop(); }
    }

    @ParameterizedTest @ValueSource(strings = {"user-receipt", "user-race", "compensation-receipt", "compensation-race"})
    void given__another_receipt_type_owns_identity__when__delivering__then__fail_closed_without_wrong_ack_or_delete(String collision) throws Exception {
        if (collision.startsWith("user")) rows.add(row("FAVORITE_ADDED"));
        else when(animals.pendingCompensations(anyLong(), anyInt())).thenReturn(List.of(compensation("ROLLBACK_FAVORITE_ADDED")));
        if (collision.endsWith("race")) raceType = "ANOTHER_PURPOSE";
        else receipts.put(collision.startsWith("user") ? OUTBOX : COMPENSATION, "ANOTHER_PURPOSE");
        assertEquals(0, delivery.poll(0, 0, 2)); assertEquals(0, deletes); verify(animals, never()).acknowledgeCompensation(any());
        if (collision.equals("user-receipt")) verify(animals, never()).applyFavorite(any());
    }

    @ParameterizedTest @ValueSource(strings = {"missing-original", "same-original", "wrong-aggregate", "wrong-topic", "unknown-type"})
    void given__untraceable_compensation__when__polling__then__leave_pending_without_user_changes_or_animal_ack(String defect) throws Exception {
        var original = compensation("ROLLBACK_FAVORITE_ADDED"); var body = json.readTree(original.payload());
        var object = (com.fasterxml.jackson.databind.node.ObjectNode) body;
        if (defect.equals("missing-original")) object.remove("originalEventId");
        if (defect.equals("same-original")) object.put("originalEventId", COMPENSATION);
        if (defect.equals("unknown-type")) object.put("compensationType", "UNKNOWN");
        var malformed = new CompensationEnvelope(original.outboxId(), original.outboxEventId(), defect.equals("wrong-aggregate") ? "99" : original.aggregateId(),
                original.eventType(), defect.equals("wrong-topic") ? "other" : original.topic(), json.writeValueAsString(body));
        when(animals.pendingCompensations(anyLong(), anyInt())).thenReturn(List.of(malformed));
        assertEquals(0, delivery.poll(0, 0, 2)); assertTrue(receipts.isEmpty()); assertEquals(0, deletes); verify(animals, never()).acknowledgeCompensation(any());
    }

    @ParameterizedTest @ValueSource(strings = {"before", "after-apply", "during-pause", "interrupt-pause", "after-compensation-commit"})
    void given__cooperative_stop_or_interrupt__when__work_is_in_flight__then__preserve_pending_and_do_not_start_next_cross_module_phase(String phase) throws Exception {
        rows.add(row("FAVORITE_ADDED"));
        if (phase.equals("before")) delivery.stop();
        if (phase.equals("after-apply")) doAnswer(call -> { delivery.stop(); return null; }).when(animals).applyFavorite(any());
        if (phase.endsWith("pause")) {
            delivery = new JdbcFavoriteEventDelivery(jdbc, tx, animals, json, () -> { assertFalse(tx.active); if (phase.equals("interrupt-pause")) Thread.currentThread().interrupt(); else delivery.stop(); });
            doThrow(new IllegalStateException("synthetic failure")).when(animals).applyFavorite(any());
        }
        if (phase.equals("after-compensation-commit")) {
            rows.clear(); when(animals.pendingCompensations(anyLong(), anyInt())).thenReturn(List.of(compensation("ROLLBACK_FAVORITE_ADDED")));
            tx.stopAfterReceiptCommit = true;
        }
        assertEquals(0, delivery.poll(0, 0, 2)); verify(animals, never()).recoverAdded(any()); verify(animals, never()).acknowledgeCompensation(any());
        if (phase.equals("interrupt-pause")) assertTrue(Thread.currentThread().isInterrupted());
        if (phase.equals("before")) verifyNoInteractions(jdbc, animals);
        else if (!phase.equals("after-compensation-commit")) assertTrue(receipts.isEmpty());
    }

    @Test void given__late_lower_id_and_processed_prefix__when__polling_again__then__reuse_fixed_floor_and_select_pending_aggregate_heads() throws Exception {
        rows.add(row("FAVORITE_ADDED")); assertEquals(1, delivery.poll(5, 6, 1));
        var late = row("FAVORITE_REMOVED"); late.put("outbox_event_id", 10L); late.put("event_id", "55555555-5555-4555-8555-555555555555");
        late.put("payload", json.writeValueAsString(Map.of("eventId", "66666666-6666-4666-8666-666666666666", "eventType", "FAVORITE_REMOVED", "userId", 7L, "animalId", 90L)));
        rows.add(late);
        assertEquals(1, delivery.poll(5, 6, 1));
        assertEquals(2, pendingArguments.size()); pendingArguments.forEach(args -> assertArrayEquals(new Object[]{5L, 20L, 5L, 1}, args));
        for (String sql : pendingSql) {
            assertTrue(sql.contains("row_number() OVER (PARTITION BY o.aggregate_id ORDER BY o.outbox_event_id)"));
            assertTrue(sql.contains("NOT EXISTS")); assertTrue(sql.contains("p.event_id=o.event_id AND p.event_type='LOCAL_FAVORITE_DELIVERED'"));
            assertTrue(sql.contains("WHERE pending_rank=1")); assertFalse(sql.contains("pawbridge_animal"));
            assertTrue(sql.contains("o.outbox_event_id <= ?"));
            assertTrue(sql.contains("WHERE pending_rank=1 AND outbox_event_id>?"));
        }
        verify(animals).applyFavorite(new FavoriteEvent("66666666-6666-4666-8666-666666666666", 7, 90, "FAVORITE_REMOVED"));
    }

    @Test void given__a_failed_head_and_another_aggregate__when__polling_with_limit_one__then__advance_selection_without_acknowledging_the_failed_head() throws Exception {
        var poison = sourceRow(20, 7, "FAVORITE_REMOVED");
        var healthy = sourceRow(21, 8, "FAVORITE_ADDED");
        var blocked = sourceRow(22, 7, "FAVORITE_ADDED");
        rows.addAll(List.of(poison, healthy, blocked));
        selections.add(List.of(poison)); selections.add(List.of(healthy)); selections.add(List.of());
        doAnswer(call -> {
            assertFalse(tx.active);
            if (((FavoriteEvent) call.getArgument(0)).eventType().equals("FAVORITE_REMOVED")) throw new IllegalStateException("synthetic failure");
            return null;
        }).when(animals).applyFavorite(any());
        assertEquals(0, delivery.poll(0, 0, 1));
        assertEquals(1, delivery.poll(0, 0, 1));
        assertEquals(0, delivery.poll(0, 0, 1));
        assertEquals(Map.of(healthy.get("event_id"), DELIVERED), receipts);
        verify(animals, times(4)).applyFavorite(new FavoriteEvent(new java.util.UUID(1, 20).toString(), 7, 90, "FAVORITE_REMOVED"));
        verify(animals).applyFavorite(new FavoriteEvent(new java.util.UUID(1, 21).toString(), 8, 90, "FAVORITE_ADDED"));
        verify(animals, never()).applyFavorite(new FavoriteEvent(new java.util.UUID(1, 22).toString(), 7, 90, "FAVORITE_ADDED"));
        assertArrayEquals(new Object[]{0L, 22L, 0L, 1}, pendingArguments.get(0));
        assertArrayEquals(new Object[]{0L, 22L, 20L, 1}, pendingArguments.get(1));
        assertArrayEquals(new Object[]{0L, 22L, 21L, 1}, pendingArguments.get(2));
        assertEquals(1, upperArguments.size());
        String sql = pendingSql.getFirst();
        assertTrue(sql.indexOf("o.outbox_event_id <= ?") < sql.indexOf("pending_rank=1"));
        assertTrue(sql.indexOf("pending_rank=1") < sql.indexOf("AND outbox_event_id>?"));
    }

    @Test void given__new_tail_rows_and_a_late_lower_commit__when__an_epoch_reaches_its_fixed_upper__then__wrap_and_discover_the_late_row() throws Exception {
        var first = sourceRow(20, 7, "FAVORITE_ADDED"); var last = sourceRow(40, 8, "FAVORITE_ADDED");
        rows.addAll(List.of(first, last)); selections.add(List.of(first)); selections.add(List.of(last));
        assertEquals(1, delivery.poll(5, 0, 1));
        var late = sourceRow(10, 9, "FAVORITE_ADDED"); var tail = sourceRow(60, 10, "FAVORITE_ADDED");
        rows.addAll(List.of(late, tail)); selections.add(List.of(late)); selections.add(List.of(tail));
        assertEquals(1, delivery.poll(5, 0, 1));
        assertEquals(1, delivery.poll(5, 0, 1));
        rows.add(sourceRow(80, 11, "FAVORITE_ADDED"));
        assertEquals(1, delivery.poll(5, 0, 1));
        assertEquals(2, upperArguments.size());
        assertArrayEquals(new Object[]{5L, 40L, 5L, 1}, pendingArguments.get(0));
        assertArrayEquals(new Object[]{5L, 40L, 20L, 1}, pendingArguments.get(1));
        assertArrayEquals(new Object[]{5L, 60L, 5L, 1}, pendingArguments.get(2));
        assertArrayEquals(new Object[]{5L, 60L, 10L, 1}, pendingArguments.get(3));
        assertEquals(DELIVERED, receipts.get(late.get("event_id")));
        assertFalse(receipts.containsKey(new java.util.UUID(2, 80).toString()));
    }

    @Test void given__a_failed_selection_and_a_changed_floor__when__polling_again__then__keep_the_cursor_on_failure_and_reset_for_the_new_boundary() throws Exception {
        var first = sourceRow(20, 7, "FAVORITE_ADDED"); var next = sourceRow(30, 8, "FAVORITE_ADDED");
        rows.addAll(List.of(first, next, sourceRow(40, 10, "FAVORITE_ADDED")));
        selections.add(List.of(first)); selections.add(List.of(next));
        assertEquals(1, delivery.poll(15, 0, 1));
        failNextSelection = true;
        assertThrows(IllegalStateException.class, () -> delivery.poll(15, 0, 1));
        assertEquals(1, delivery.poll(15, 0, 1));
        assertArrayEquals(pendingArguments.get(1), pendingArguments.get(2));
        assertArrayEquals(new Object[]{15L, 40L, 20L, 1}, pendingArguments.get(2));
        var late = sourceRow(10, 9, "FAVORITE_ADDED"); rows.add(late); selections.add(List.of(late));
        assertEquals(1, delivery.poll(5, 0, 1));
        assertArrayEquals(new Object[]{5L, 40L, 5L, 1}, pendingArguments.get(3));
        assertEquals(2, upperArguments.size());
        assertArrayEquals(new Object[]{15L, 15L}, upperArguments.get(0));
        assertArrayEquals(new Object[]{5L, 5L}, upperArguments.get(1));
    }

    @Test void given__a_poll_holds_its_selection_monitor__when__another_thread_stops_it__then__stop_returns_before_the_read_finishes() throws Exception {
        rows.add(sourceRow(20, 7, "FAVORITE_ADDED"));
        var reading = new CountDownLatch(1); var release = new CountDownLatch(1); var stopped = new CountDownLatch(1);
        duringSelection = () -> {
            reading.countDown();
            try { assertTrue(release.await(3, TimeUnit.SECONDS)); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new AssertionError(interrupted); }
        };
        var executor = Executors.newSingleThreadExecutor();
        var poll = executor.submit(() -> delivery.poll(0, 0, 1));
        Thread stopper = new Thread(() -> { delivery.stop(); stopped.countDown(); });
        try {
            assertTrue(reading.await(2, TimeUnit.SECONDS)); stopper.start();
            assertTrue(stopped.await(1, TimeUnit.SECONDS), "stop must not wait for the poll monitor");
            release.countDown(); assertEquals(0, poll.get(2, TimeUnit.SECONDS));
            verifyNoInteractions(animals); assertTrue(receipts.isEmpty());
        } finally {
            release.countDown(); stopper.join(2000); poll.cancel(true); executor.shutdownNow();
            assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS));
        }
    }

    @Test void given__same_type_receipt_wins_concurrently__when__compensating__then__skip_pair_delete_and_allow_ack() throws Exception {
        raceType = "ROLLBACK_FAVORITE_ADDED"; var envelope = compensation(raceType);
        when(animals.pendingCompensations(anyLong(), anyInt())).thenReturn(List.of(envelope));
        assertEquals(1, delivery.poll(0, 0, 1)); assertEquals(0, deletes); verify(animals).acknowledgeCompensation(envelope);
    }

    @Test void given__multiple_unresolved_rows_with_private_details__when__polling__then__emit_one_count_only_warning_and_keep_processing_other_aggregates() throws Exception {
        rows.add(row("FAVORITE_ADDED"));
        var malformed = row("FAVORITE_ADDED"); malformed.put("outbox_event_id", 21L); malformed.put("aggregate_id", "8");
        malformed.put("payload", "private-row-secret"); rows.add(malformed);
        doThrow(new IllegalStateException("private-driver-password=synthetic")).when(animals).applyFavorite(any());
        doThrow(new IllegalStateException("private-recovery-secret")).when(animals).recoverAdded(any());
        var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(JdbcFavoriteEventDelivery.class);
        var appender = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        appender.start(); logger.addAppender(appender);
        try {
            assertEquals(0, delivery.poll(0, 0, 2));
            assertEquals(1, appender.list.size());
            var event = appender.list.getFirst();
            assertEquals(ch.qos.logback.classic.Level.WARN, event.getLevel());
            assertEquals("Local favorite delivery has 2 unresolved operations", event.getFormattedMessage());
            assertNull(event.getThrowableProxy());
            assertFalse(event.getFormattedMessage().contains(PAYLOAD)); assertFalse(event.getFormattedMessage().contains(OUTBOX));
            assertFalse(event.getFormattedMessage().contains("private")); assertTrue(receipts.isEmpty());
        } finally { logger.detachAppender(appender); appender.stop(); }
    }

    private Map<String, Object> row(String type) throws Exception {
        var result = new LinkedHashMap<String, Object>(); result.put("outbox_event_id", 20L); result.put("event_id", OUTBOX);
        result.put("aggregate_type", "Favorite"); result.put("aggregate_id", "7"); result.put("event_type", type);
        result.put("topic", "user.favorite.events"); result.put("payload", payload(type)); return result;
    }
    private Map<String, Object> sourceRow(long sequence, long member, String type) throws Exception {
        var result = row(type); result.put("outbox_event_id", sequence);
        result.put("event_id", new java.util.UUID(2, sequence).toString()); result.put("aggregate_id", Long.toString(member));
        result.put("payload", json.writeValueAsString(Map.of("eventId", new java.util.UUID(1, sequence).toString(),
                "eventType", type, "userId", member, "animalId", 90L)));
        return result;
    }
    private String payload(String type) throws Exception {
        return json.writeValueAsString(Map.of("eventId", PAYLOAD, "eventType", type, "userId", 7L, "animalId", 90L, "timestamp", "2026-10-20T20:00:00"));
    }
    private CompensationEnvelope compensation(String type) throws Exception {
        return new CompensationEnvelope(30, COMPENSATION_OUTBOX, "7", "FAVORITE_COMPENSATION_REQUIRED", "user.compensation.events",
                json.writeValueAsString(Map.of("eventId", COMPENSATION, "originalEventId", PAYLOAD, "eventType", "FAVORITE_COMPENSATION_REQUIRED", "compensationType", type, "userId", 7L, "animalId", 90L)));
    }

    private final class Tx implements TransactionOperations {
        boolean active, failReceiptCommit, persistOnFailure, stopAfterReceiptCommit;
        @Override public <T> T execute(TransactionCallback<T> callback) {
            assertFalse(active); active = true; calls.add("begin");
            var previous = new HashMap<>(receipts); int previousDeletes = deletes;
            try {
                T result = callback.doInTransaction(new SimpleTransactionStatus());
                boolean changed = !previous.equals(receipts);
                if (changed && failReceiptCommit) {
                    failReceiptCommit = false;
                    if (!persistOnFailure) { receipts.clear(); receipts.putAll(previous); deletes = previousDeletes; }
                    throw new IllegalStateException("synthetic commit response loss");
                }
                calls.add("commit");
                if (changed && stopAfterReceiptCommit) delivery.stop();
                return result;
            } catch (RuntimeException failure) {
                if (!persistOnFailure) { receipts.clear(); receipts.putAll(previous); deletes = previousDeletes; }
                throw failure;
            } finally { active = false; calls.add("end"); }
        }
    }
}
