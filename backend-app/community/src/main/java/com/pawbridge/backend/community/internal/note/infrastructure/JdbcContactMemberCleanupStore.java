package com.pawbridge.backend.community.internal.note.infrastructure;

import com.pawbridge.backend.community.internal.note.application.ContactMemberCleanupStore;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.function.LongConsumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

public final class JdbcContactMemberCleanupStore implements ContactMemberCleanupStore {
    private static final Logger log = LoggerFactory.getLogger(JdbcContactMemberCleanupStore.class);
    private static final int CLEANUP_BATCH_SIZE = 300;

    private final JdbcOperations jdbc;
    private final TransactionOperations transaction;
    private final LongConsumer committedCleanup;

    public JdbcContactMemberCleanupStore(JdbcOperations jdbc, TransactionOperations transaction,
            LongConsumer committedCleanup) {
        this.jdbc = jdbc;
        this.transaction = transaction;
        this.committedCleanup = committedCleanup;
    }

    @Override
    public void remove(long memberId, Runnable requireDeletionPending) {
        transaction.executeWithoutResult(status -> {
            jdbc.update("""
                    INSERT INTO pawbridge_community.private_note_members(member_id)
                    VALUES (?) ON CONFLICT DO NOTHING
                    """, memberId);
            jdbc.queryForObject("""
                    SELECT member_id FROM pawbridge_community.private_note_members
                    WHERE member_id = ? FOR UPDATE
                    """, Long.class, memberId);
            requireDeletionPending.run();
            withdrawNotes(memberId);
            if (jdbc.queryForObject("SELECT to_regclass('pawbridge_community.member_chat_rooms') IS NOT NULL", Boolean.class)) {
                withdrawChat(memberId);
            }
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    try {
                        committedCleanup.accept(memberId);
                    } catch (RuntimeException failure) {
                        log.warn("Private note push failed ({}); REST restoration remains available",
                                failure.getClass().getSimpleName());
                    }
                }
            });
        });
    }

    private void withdrawNotes(long memberId) {
        List<UUID> affectedNotes = jdbc.query("""
                SELECT note_id FROM pawbridge_community.private_notes
                WHERE sender_id = ? OR recipient_id = ? ORDER BY note_id FOR UPDATE
                """, (row, rowNumber) -> row.getObject("note_id", UUID.class), memberId, memberId);
        jdbc.update("""
                UPDATE pawbridge_community.private_note_requests SET request_hash = NULL
                WHERE note_id IN (
                    SELECT note_id FROM pawbridge_community.private_notes WHERE sender_id = ? OR recipient_id = ?
                )
                """, memberId, memberId);
        jdbc.update("""
                UPDATE pawbridge_community.private_notes SET context_type = NULL, context_id = NULL
                WHERE sender_id = ? OR recipient_id = ?
                """, memberId, memberId);
        jdbc.update("DELETE FROM pawbridge_community.private_note_members WHERE member_id = ?", memberId);
        for (int start = 0; start < affectedNotes.size(); start += CLEANUP_BATCH_SIZE) {
            List<UUID> batch = affectedNotes.subList(start, Math.min(start + CLEANUP_BATCH_SIZE, affectedNotes.size()));
            String placeholders = String.join(",", Collections.nCopies(batch.size(), "?"));
            String sql = """
                    DELETE FROM pawbridge_community.private_notes n
                    WHERE note_id IN (%s) AND NOT EXISTS(
                        SELECT 1 FROM pawbridge_community.private_note_mailboxes m WHERE m.note_id = n.note_id
                    )
                    """.formatted(placeholders);
            jdbc.update(sql, batch.toArray());
        }
    }

    private void withdrawChat(long memberId) {
        List<UUID> rooms = jdbc.queryForList("SELECT room_id FROM pawbridge_community.member_chat_room_members WHERE member_id=?", UUID.class, memberId);
        jdbc.update("DELETE FROM pawbridge_community.member_chat_room_members WHERE member_id=?", memberId);
        jdbc.update("DELETE FROM pawbridge_community.member_chat_requests WHERE sender_id=?", memberId);
        jdbc.update("UPDATE pawbridge_community.member_chat_messages SET sender_id=NULL WHERE sender_id=?", memberId);
        jdbc.update("UPDATE pawbridge_community.member_chat_rooms SET first_member_id=NULL WHERE first_member_id=?", memberId);
        jdbc.update("UPDATE pawbridge_community.member_chat_rooms SET second_member_id=NULL WHERE second_member_id=?", memberId);
        for (UUID room : rooms) {
            jdbc.update("""
                    DELETE FROM pawbridge_community.member_chat_rooms r WHERE room_id=? AND NOT EXISTS(
                      SELECT 1 FROM pawbridge_community.member_chat_room_members a WHERE a.room_id=r.room_id)
                    """, room);
        }
    }
}
