package com.pawbridge.backend.community.internal.note.infrastructure;

import com.pawbridge.backend.community.internal.note.application.NoteBlockRemoval;
import java.util.stream.LongStream;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.transaction.support.TransactionOperations;

public final class JdbcNoteBlockRemoval implements NoteBlockRemoval {
    private final JdbcOperations jdbc;
    private final TransactionOperations transaction;

    public JdbcNoteBlockRemoval(JdbcOperations jdbc, TransactionOperations transaction) {
        this.jdbc = jdbc;
        this.transaction = transaction;
    }

    @Override
    public void remove(long currentUserId, long blockedUserId, Runnable requireActiveCurrent) {
        transaction.executeWithoutResult(status -> {
            for (long memberId : LongStream.of(currentUserId, blockedUserId).distinct().sorted().toArray()) {
                jdbc.update("""
                        INSERT INTO pawbridge_community.private_note_members(member_id)
                        VALUES (?) ON CONFLICT DO NOTHING
                        """, memberId);
                jdbc.queryForObject("""
                        SELECT member_id FROM pawbridge_community.private_note_members
                        WHERE member_id = ? FOR UPDATE
                        """, Long.class, memberId);
            }
            requireActiveCurrent.run();
            jdbc.update("""
                    DELETE FROM pawbridge_community.private_note_blocks WHERE member_id = ? AND blocked_id = ?
                    """, currentUserId, blockedUserId);
        });
    }
}
