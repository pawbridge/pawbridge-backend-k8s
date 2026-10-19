package com.pawbridge.backend.community.internal.note.infrastructure;

import com.pawbridge.backend.community.internal.note.application.NoteDeliveryHints;
import com.pawbridge.backend.community.internal.note.application.NoteNotifications.Notification;
import com.pawbridge.backend.community.internal.note.application.NoteSendStore;
import com.pawbridge.backend.community.internal.note.application.SendPrivateNote.Receipt;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.LongStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

public final class JdbcNoteSendStore implements NoteSendStore {
    private static final Logger log = LoggerFactory.getLogger(JdbcNoteSendStore.class);
    private final JdbcOperations jdbc;
    private final TransactionOperations transaction;
    private final NoteDeliveryHints hints;

    public JdbcNoteSendStore(JdbcOperations jdbc, TransactionOperations transaction, NoteDeliveryHints hints) {
        this.jdbc = jdbc;
        this.transaction = transaction;
        this.hints = hints;
    }

    @Override
    public Receipt inTransaction(Supplier<Receipt> action) {
        return transaction.execute(status -> action.get());
    }

    @Override
    public void lockMembers(long firstMemberId, long secondMemberId) {
        for (long memberId : LongStream.of(firstMemberId, secondMemberId).distinct().sorted().toArray()) {
            jdbc.update("""
                    INSERT INTO pawbridge_community.private_note_members(member_id)
                    VALUES (?) ON CONFLICT DO NOTHING
                    """, memberId);
            jdbc.queryForObject("""
                    SELECT member_id FROM pawbridge_community.private_note_members
                    WHERE member_id = ? FOR UPDATE
                    """, Long.class, memberId);
        }
    }

    @Override
    public Optional<PreviousRequest> request(long senderId, UUID requestId) {
        return jdbc.query("""
                SELECT note_id, request_hash FROM pawbridge_community.private_note_requests
                WHERE sender_id = ? AND request_id = ?
                """, (row, number) -> new PreviousRequest(row.getObject("note_id", UUID.class), row.getString("request_hash")),
                senderId, requestId).stream().findFirst();
    }

    @Override
    public boolean blocked(long firstMemberId, long secondMemberId) {
        return jdbc.queryForObject("""
                SELECT EXISTS(
                    SELECT 1 FROM pawbridge_community.private_note_blocks
                    WHERE (member_id = ? AND blocked_id = ?) OR (member_id = ? AND blocked_id = ?)
                )
                """, Boolean.class, firstMemberId, secondMemberId, secondMemberId, firstMemberId);
    }

    @Override
    public long rate(long senderId, Instant since) {
        return jdbc.queryForObject("""
                SELECT count(*) FROM pawbridge_community.private_note_requests
                WHERE sender_id = ? AND created_at >= ?
                """, Long.class, senderId, Timestamp.from(since));
    }

    @Override
    public Optional<Reply> reply(long ownerId, UUID noteId, Instant now) {
        return jdbc.query("""
                SELECT m.direction, n.sender_id
                FROM pawbridge_community.private_note_mailboxes m
                JOIN pawbridge_community.private_notes n ON n.note_id = m.note_id
                WHERE m.member_id = ? AND n.note_id = ? AND n.expires_at > ?
                """, (row, number) -> new Reply(row.getString("direction"), (Long) row.getObject("sender_id")),
                ownerId, noteId, Timestamp.from(now)).stream().findFirst();
    }

    @Override
    public boolean contextAuthor(String contextType, long contextId, long recipientId) {
        // Table names come only from the application's validated POST/REPORT values.
        String table = "POST".equals(contextType) ? "posts" : "animal_reports";
        String idColumn = "POST".equals(contextType) ? "post_id" : "report_id";
        String sql = """
                SELECT EXISTS(
                    SELECT 1 FROM pawbridge_community.%s
                    WHERE %s = ? AND author_id = ? AND deleted_at IS NULL
                )
                """.formatted(table, idColumn);
        return jdbc.queryForObject(sql, Boolean.class, contextId, recipientId);
    }

    @Override
    public void insert(NewNote note, Notification notification) {
        jdbc.update("""
                INSERT INTO pawbridge_community.private_notes(
                    note_id, sender_id, recipient_id, body, reply_to,
                    context_type, context_id, created_at, expires_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, note.noteId(), note.senderId(), note.recipientId(), note.body(), note.replyTo(),
                note.contextType(), note.contextId(), Timestamp.from(note.createdAt()), Timestamp.from(note.expiresAt()));
        jdbc.update("""
                INSERT INTO pawbridge_community.private_note_mailboxes(
                    member_id, note_id, direction, read_at, created_at
                ) VALUES (?, ?, 'SENT', ?, ?), (?, ?, 'INBOX', NULL, ?)
                """, note.senderId(), note.noteId(), Timestamp.from(note.createdAt()), Timestamp.from(note.createdAt()),
                note.recipientId(), note.noteId(), Timestamp.from(note.createdAt()));
        jdbc.update("""
                INSERT INTO pawbridge_community.private_note_requests(
                    sender_id, request_id, request_hash, note_id, created_at
                ) VALUES (?, ?, ?, ?, ?)
                """, note.senderId(), note.requestId(), note.requestHash(), note.noteId(), Timestamp.from(note.createdAt()));
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    hints.publish(note.recipientId(), notification);
                } catch (RuntimeException failure) {
                    log.warn("Private note push failed ({}); REST restoration remains available", failure.getClass().getSimpleName());
                }
            }
        });
    }
}
