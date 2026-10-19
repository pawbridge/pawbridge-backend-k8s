package com.pawbridge.backend.community.internal.note.infrastructure;

import com.pawbridge.backend.community.internal.note.application.NoteNotificationReader;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.transaction.support.TransactionOperations;

public final class JdbcNoteNotificationReader implements NoteNotificationReader {
    private static final String MAILBOX_JOIN = """
             FROM pawbridge_community.private_note_mailboxes m
             JOIN pawbridge_community.private_notes n ON n.note_id = m.note_id
            """;
    private final JdbcOperations jdbc;
    private final TransactionOperations readOnlyTransaction;

    public JdbcNoteNotificationReader(JdbcOperations jdbc, TransactionOperations readOnlyTransaction) {
        this.jdbc = jdbc;
        this.readOnlyTransaction = readOnlyTransaction;
    }

    @Override
    public List<Row> read(long currentUserId, UUID cursor, Instant now) {
        return readOnlyTransaction.execute(status -> {
            if (cursor == null) {
                String sql = "SELECT n.note_id, n.sender_id, n.created_at, m.read_at" + MAILBOX_JOIN + """
                        WHERE m.member_id = ? AND m.direction = 'INBOX' AND n.expires_at > ?
                        ORDER BY m.created_at DESC, m.note_id DESC LIMIT 21
                        """;
                return jdbc.query(sql, JdbcNoteNotificationReader::mapRow, currentUserId, Timestamp.from(now));
            }
            String sql = "SELECT n.note_id, n.sender_id, n.created_at, m.read_at" + MAILBOX_JOIN + """
                    WHERE m.member_id = ? AND m.direction = 'INBOX' AND n.expires_at > ?
                      AND (m.created_at, m.note_id) < (
                          SELECT created_at, note_id FROM pawbridge_community.private_note_mailboxes
                          WHERE member_id = ? AND note_id = ? AND direction = 'INBOX'
                      )
                    ORDER BY m.created_at DESC, m.note_id DESC LIMIT 21
                    """;
            return jdbc.query(sql, JdbcNoteNotificationReader::mapRow,
                    currentUserId, Timestamp.from(now), currentUserId, cursor);
        });
    }

    @Override
    public long unread(long currentUserId, Instant now) {
        return readOnlyTransaction.execute(status -> jdbc.queryForObject("SELECT count(*)" + MAILBOX_JOIN + """
                WHERE m.member_id = ? AND m.direction = 'INBOX' AND m.read_at IS NULL AND n.expires_at > ?
                """, Long.class, currentUserId, Timestamp.from(now)));
    }

    private static Row mapRow(ResultSet row, int rowNumber) throws SQLException {
        Timestamp readAt = row.getTimestamp("read_at");
        return new Row(row.getObject("note_id", UUID.class), (Long) row.getObject("sender_id"),
                row.getTimestamp("created_at").toInstant(), readAt == null ? null : readAt.toInstant());
    }
}
