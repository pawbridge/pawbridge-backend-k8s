package com.pawbridge.backend.community.internal.post.infrastructure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pawbridge.backend.community.internal.post.application.PostSnapshot;
import com.pawbridge.backend.community.internal.post.application.UnsupportedPostCharactersException;
import com.pawbridge.backend.community.internal.post.domain.PostBoardType;
import com.pawbridge.backend.community.internal.post.domain.PostToCreate;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.postgresql.util.PSQLException;
import org.postgresql.util.ServerErrorMessage;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.jdbc.core.PreparedStatementCreator;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionOperations;

class JdbcPostWriterTest {
    private final JdbcOperations jdbc = mock(JdbcOperations.class);
    private final TransactionOperations transactions = mock(TransactionOperations.class);
    private final Connection connection = mock(Connection.class);
    private final ResultSet result = mock(ResultSet.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private final Clock clock = Clock.fixed(Instant.parse("2026-10-18T03:30:10.123456789Z"), ZoneId.of("Asia/Seoul"));
    private final List<StatementCall> calls = new ArrayList<>();
    private boolean transactionActive;
    private int failAtStatement;
    private final RuntimeException writeFailure = new DataAccessResourceFailureException("synthetic write failure");

    @BeforeEach
    void prepareTransactionAndJdbc() throws Exception {
        when(transactions.execute(ArgumentMatchers.<TransactionCallback<PostSnapshot>>any()))
                .thenAnswer(invocation -> {
                    TransactionCallback<PostSnapshot> callback = invocation.getArgument(0);
                    transactionActive = true;
                    try {
                        return callback.doInTransaction(new SimpleTransactionStatus());
                    } finally {
                        transactionActive = false;
                    }
                });
        when(connection.prepareStatement(anyString())).thenAnswer(invocation -> {
            assertTrue(transactionActive);
            StatementCall call = new StatementCall(invocation.getArgument(0));
            calls.add(call);
            PreparedStatement statement = mock(PreparedStatement.class);
            doAnswer(binding -> {
                call.values.put(binding.getArgument(0), binding.getArgument(1));
                return null;
            }).when(statement).setObject(anyInt(), any());
            doAnswer(binding -> {
                call.values.put(binding.getArgument(0), binding.getArgument(1));
                call.calendars.put(binding.getArgument(0), binding.getArgument(2));
                return null;
            }).when(statement).setTimestamp(anyInt(), any(Timestamp.class), any(Calendar.class));
            return statement;
        });
        when(result.next()).thenReturn(true);
        when(result.getLong("post_id")).thenReturn(42L);
        when(jdbc.query(any(PreparedStatementCreator.class), ArgumentMatchers.<ResultSetExtractor<Long>>any()))
                .thenAnswer(invocation -> {
                    invocation.getArgument(0, PreparedStatementCreator.class).createPreparedStatement(connection);
                    if (failAtStatement == calls.size()) {
                        throw writeFailure;
                    }
                    return invocation.getArgument(1, ResultSetExtractor.class).extractData(result);
                });
        when(jdbc.update(any(PreparedStatementCreator.class))).thenAnswer(invocation -> {
            invocation.getArgument(0, PreparedStatementCreator.class).createPreparedStatement(connection);
            if (failAtStatement == calls.size()) {
                throw writeFailure;
            }
            return 1;
        });
    }

    @Test
    void given__post_and_media__when__write__then__identity_json_event_and_weighted_projection_share_transaction() throws Exception {
        PostSnapshot post;
        try (JdbcPostWriter writer = writer()) {
            post = writer.create(input());
        }

        assertFalse(transactionActive);
        assertEquals(3, calls.size());
        StatementCall source = calls.get(0);
        assertTrue(source.sql.contains("pawbridge_community.posts"));
        assertTrue(source.sql.contains("RETURNING post_id"));
        assertEquals(7L, source.values.get(1));
        assertEquals("보호 동물", source.values.get(2));
        assertEquals("새 가족을 찾습니다", source.values.get(3));
        assertEquals("PROTECTION", source.values.get(4));
        assertEquals(mapper.valueToTree(input().imageUrls()), mapper.readTree((String) source.values.get(5)));
        assertEquals(new PostSnapshot(42L, 7L, "보호 동물", "새 가족을 찾습니다", "PROTECTION",
                input().imageUrls(), LocalDateTime.now(clock), LocalDateTime.now(clock)), post);
        assertEquals(Timestamp.valueOf(post.createdAt()), source.values.get(6));
        assertEquals(Timestamp.valueOf(post.updatedAt()), source.values.get(7));
        assertEquals("UTC", source.calendars.get(6).getTimeZone().getID());
        assertEquals("UTC", source.calendars.get(7).getTimeZone().getID());

        StatementCall outbox = calls.get(1);
        assertTrue(outbox.sql.contains("pawbridge_community.outbox_events"));
        assertEquals(UUID.fromString((String) outbox.values.get(1)).toString(), outbox.values.get(1));
        assertEquals("Post", outbox.values.get(2));
        assertEquals("42", outbox.values.get(3));
        assertEquals("POST_CREATED", outbox.values.get(4));
        JsonNode payload = mapper.readTree((String) outbox.values.get(5));
        assertEquals(mapper.valueToTree(Map.of("postId", 42L, "authorId", 7L, "title", input().title(),
                "content", input().content(), "boardType", "PROTECTION", "imageUrls", input().imageUrls())), payload);
        assertEquals("UTC", outbox.calendars.get(6).getTimeZone().getID());

        StatementCall search = calls.get(2);
        assertTrue(search.sql.contains("pawbridge_community.post_search_documents"));
        assertTrue(search.sql.contains("md5(?)"));
        assertTrue(search.sql.contains("setweight(to_tsvector('simple', ?), 'A')"));
        assertTrue(search.sql.contains("setweight(to_tsvector('simple', ?), 'B')"));
        assertEquals(42L, search.values.get(1));
        assertEquals(input().title(), search.values.get(2));
        assertEquals(input().content(), search.values.get(3));
        assertEquals("nori-9.12.3-v1", search.values.get(4));
        assertFalse(((String) search.values.get(5)).isBlank());
        assertFalse(((String) search.values.get(6)).isBlank());
    }

    @Test
    void given__outbox_failure__when__write__then__failure_leaves_transaction_without_search_or_retry() {
        failAtStatement = 2;
        try (JdbcPostWriter writer = writer()) {
            assertSame(writeFailure, assertThrows(RuntimeException.class, () -> writer.create(input())));
        }
        assertEquals(2, calls.size());
        assertFalse(transactionActive);
    }

    @Test
    void given__search_failure__when__write__then__failure_reaches_transaction_without_returning_success() {
        failAtStatement = 3;
        try (JdbcPostWriter writer = writer()) {
            assertSame(writeFailure, assertThrows(RuntimeException.class, () -> writer.create(input())));
        }
        assertEquals(3, calls.size());
        assertFalse(transactionActive);
    }

    @Test
    void given__commit_charset_failure__when__write__then__only_named_community_constraint_becomes_user_error() {
        TransactionSystemException failure = new TransactionSystemException("commit", charset("23514", "pawbridge_community", "ck_rollback_charset_posts"));
        when(transactions.execute(ArgumentMatchers.<TransactionCallback<PostSnapshot>>any())).thenThrow(failure);
        try (JdbcPostWriter writer = writer()) {
            UnsupportedPostCharactersException translated = assertThrows(UnsupportedPostCharactersException.class,
                    () -> writer.create(input()));
            assertSame(failure, translated.getCause());
            assertEquals("일부 이모지 등 지원하지 않는 문자를 제외해 주세요.", translated.getMessage());
        }
    }

    @Test
    void given__unrelated_check_failure_at_commit__when__write__then__original_failure_is_not_a_user_error() {
        TransactionSystemException failure = new TransactionSystemException("commit", charset("23514", "pawbridge_community", "business_constraint"));
        when(transactions.execute(ArgumentMatchers.<TransactionCallback<PostSnapshot>>any())).thenThrow(failure);
        try (JdbcPostWriter writer = writer()) {
            assertSame(failure, assertThrows(TransactionSystemException.class, () -> writer.create(input())));
        }
    }

    private JdbcPostWriter writer() {
        return new JdbcPostWriter(jdbc, transactions, mapper, clock);
    }

    private PostToCreate input() {
        return new PostToCreate(7L, "보호 동물", "새 가족을 찾습니다", PostBoardType.PROTECTION,
                List.of("https://example.test/한글.png", "https://example.test/movie.mp4"));
    }

    private PSQLException charset(String state, String schema, String constraint) {
        return new PSQLException(new ServerErrorMessage("SERROR\0C" + state + "\0Msynthetic\0s" + schema + "\0n" + constraint + "\0"));
    }

    private static final class StatementCall {
        private final String sql;
        private final Map<Integer, Object> values = new LinkedHashMap<>();
        private final Map<Integer, Calendar> calendars = new LinkedHashMap<>();
        private StatementCall(String sql) { this.sql = sql; }
    }
}
