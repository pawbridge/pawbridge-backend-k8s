package com.pawbridge.backend.community.internal.note.infrastructure;

import com.pawbridge.backend.community.internal.note.application.NoteChangeHints;
import com.pawbridge.backend.community.internal.note.application.NoteReadMutation;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

public final class JdbcNoteReadMutation implements NoteReadMutation {
    private static final Logger log = LoggerFactory.getLogger(JdbcNoteReadMutation.class);
    private final JdbcOperations jdbc;
    private final TransactionOperations transaction;
    private final NoteChangeHints hints;

    public JdbcNoteReadMutation(JdbcOperations jdbc, TransactionOperations transaction, NoteChangeHints hints) {
        this.jdbc = jdbc;
        this.transaction = transaction;
        this.hints = hints;
    }

    @Override
    public void read(long currentUserId, UUID noteId, Runnable requireActive, Supplier<Instant> now) {
        transaction.executeWithoutResult(status -> {
            jdbc.update("""
                    INSERT INTO pawbridge_community.private_note_members(member_id)
                    VALUES (?) ON CONFLICT DO NOTHING
                    """, currentUserId);
            jdbc.queryForObject("""
                    SELECT member_id FROM pawbridge_community.private_note_members
                    WHERE member_id = ? FOR UPDATE
                    """, Long.class, currentUserId);
            requireActive.run();
            requireOwned(currentUserId, noteId, now.get());
            // A concurrently removed note may yield no row; the second ownership check decides absence.
            jdbc.query("""
                    SELECT note_id FROM pawbridge_community.private_notes
                    WHERE note_id = ? FOR UPDATE
                    """, (row, number) -> Boolean.TRUE, noteId);
            requireOwned(currentUserId, noteId, now.get());
            jdbc.update("""
                    UPDATE pawbridge_community.private_note_mailboxes SET read_at = COALESCE(read_at, ?)
                    WHERE member_id = ? AND note_id = ? AND direction = 'INBOX'
                    """, Timestamp.from(now.get()), currentUserId, noteId);
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    try {
                        hints.resync(currentUserId);
                    } catch (RuntimeException failure) {
                        log.warn("Private note push failed ({}); REST restoration remains available", failure.getClass().getSimpleName());
                    }
                }
            });
        });
    }

    private void requireOwned(long currentUserId, UUID noteId, Instant now) {
        if (jdbc.query("""
                SELECT 1 FROM pawbridge_community.private_note_mailboxes m
                JOIN pawbridge_community.private_notes n ON n.note_id = m.note_id
                WHERE m.member_id = ? AND n.note_id = ? AND n.expires_at > ?
                """, (row, number) -> Boolean.TRUE, currentUserId, noteId, Timestamp.from(now)).isEmpty()) {
            throw new MissingOwnedNote();
        }
    }
}
