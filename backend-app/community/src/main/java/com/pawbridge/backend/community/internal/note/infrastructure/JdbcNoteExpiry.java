package com.pawbridge.backend.community.internal.note.infrastructure;

import com.pawbridge.backend.community.internal.note.application.NoteExpiry;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.transaction.support.TransactionOperations;

public final class JdbcNoteExpiry implements NoteExpiry {
    private final JdbcOperations jdbc;
    private final TransactionOperations transaction;
    private final Clock clock;

    public JdbcNoteExpiry(JdbcOperations jdbc, TransactionOperations transaction, Clock clock) {
        this.jdbc = jdbc;
        this.transaction = transaction;
        this.clock = clock;
    }

    @Override
    public int expire() {
        return transaction.execute(status -> {
            Instant now = clock.instant();
            int removed = jdbc.update("""
                    WITH expired_notes AS MATERIALIZED (
                        SELECT note_id FROM pawbridge_community.private_notes
                        WHERE expires_at <= ? ORDER BY expires_at LIMIT 300 FOR UPDATE SKIP LOCKED
                    )
                    DELETE FROM pawbridge_community.private_notes
                    WHERE note_id IN (SELECT note_id FROM expired_notes)
                    """, Timestamp.from(now));
            jdbc.update("""
                    WITH expired_requests AS MATERIALIZED (
                        SELECT sender_id, request_id FROM pawbridge_community.private_note_requests
                        WHERE note_id IS NULL AND created_at < ?
                        ORDER BY created_at LIMIT 300 FOR UPDATE SKIP LOCKED
                    )
                    DELETE FROM pawbridge_community.private_note_requests
                    WHERE (sender_id, request_id) IN (SELECT sender_id, request_id FROM expired_requests)
                    """, Timestamp.from(now.minusSeconds(172800)));
            return removed;
        });
    }
}
