package com.pawbridge.backend.community.internal.note.infrastructure;

import com.pawbridge.backend.community.internal.note.application.PrivateNoteReader;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.transaction.support.TransactionOperations;

public final class JdbcPrivateNoteReader implements PrivateNoteReader {
    private final JdbcOperations jdbc;
    private final TransactionOperations readOnlyTransaction;

    public JdbcPrivateNoteReader(JdbcOperations jdbc, TransactionOperations readOnlyTransaction) {
        this.jdbc = jdbc;
        this.readOnlyTransaction = readOnlyTransaction;
    }

    @Override
    public Optional<StoredNote> find(long currentUserId, UUID noteId, Instant now) {
        return readOnlyTransaction.execute(status -> jdbc.query("""
                SELECT n.note_id, n.sender_id, n.recipient_id, n.body, n.context_type, n.context_id,
                       n.created_at, m.direction, m.read_at, m.favorite
                FROM pawbridge_community.private_note_mailboxes m
                JOIN pawbridge_community.private_notes n ON n.note_id = m.note_id
                WHERE m.member_id = ? AND n.note_id = ? AND n.expires_at > ?
                """, (row, rowNumber) -> {
                    Timestamp readAt = row.getTimestamp("read_at");
                    return new StoredNote(row.getObject("note_id", UUID.class), (Long) row.getObject("sender_id"),
                            (Long) row.getObject("recipient_id"), row.getString("body"), row.getString("context_type"),
                            (Long) row.getObject("context_id"), row.getTimestamp("created_at").toInstant(),
                            row.getString("direction"), readAt == null ? null : readAt.toInstant(), row.getBoolean("favorite"));
                }, currentUserId, noteId, Timestamp.from(now)).stream().findFirst());
    }
}
