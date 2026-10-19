package com.pawbridge.backend.community.internal.note.infrastructure;

import com.pawbridge.backend.community.internal.note.application.PrivateNoteListReader;
import com.pawbridge.backend.community.internal.note.application.PrivateNoteReader.StoredNote;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.transaction.support.TransactionOperations;

public final class JdbcPrivateNoteListReader implements PrivateNoteListReader {
    private final JdbcOperations jdbc;
    private final TransactionOperations readOnlyTransaction;

    public JdbcPrivateNoteListReader(JdbcOperations jdbc, TransactionOperations readOnlyTransaction) {
        this.jdbc = jdbc;
        this.readOnlyTransaction = readOnlyTransaction;
    }

    @Override
    public long count(long currentUserId, String box, boolean favorites, Instant now) {
        return readOnlyTransaction.execute(status -> jdbc.queryForObject("""
                SELECT count(*)
                FROM pawbridge_community.private_note_mailboxes m
                JOIN pawbridge_community.private_notes n ON n.note_id = m.note_id
                WHERE m.member_id = ? AND m.direction = ? AND (NOT ? OR m.favorite) AND n.expires_at > ?
                """, Long.class, currentUserId, box, favorites, Timestamp.from(now)));
    }

    @Override
    public List<StoredNote> read(long currentUserId, String box, boolean favorites, int page, Instant now) {
        return readOnlyTransaction.execute(status -> jdbc.query("""
                SELECT n.note_id, n.sender_id, n.recipient_id, n.body, n.context_type, n.context_id,
                       n.created_at, m.direction, m.read_at, m.favorite
                FROM pawbridge_community.private_note_mailboxes m
                JOIN pawbridge_community.private_notes n ON n.note_id = m.note_id
                WHERE m.member_id = ? AND m.direction = ? AND (NOT ? OR m.favorite) AND n.expires_at > ?
                ORDER BY m.created_at DESC, m.note_id DESC LIMIT 10 OFFSET ?
                """, (row, rowNumber) -> {
                    Timestamp readAt = row.getTimestamp("read_at");
                    return new StoredNote(row.getObject("note_id", UUID.class), (Long) row.getObject("sender_id"),
                            (Long) row.getObject("recipient_id"), row.getString("body"), row.getString("context_type"),
                            (Long) row.getObject("context_id"), row.getTimestamp("created_at").toInstant(),
                            row.getString("direction"), readAt == null ? null : readAt.toInstant(), row.getBoolean("favorite"));
                }, currentUserId, box, favorites, Timestamp.from(now), (long) page * 10));
    }
}
