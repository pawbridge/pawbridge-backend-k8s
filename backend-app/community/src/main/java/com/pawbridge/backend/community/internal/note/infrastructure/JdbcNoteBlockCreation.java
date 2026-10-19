package com.pawbridge.backend.community.internal.note.infrastructure;

import com.pawbridge.backend.community.internal.note.application.NoteBlockCreation;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.function.Supplier;
import java.util.stream.LongStream;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.transaction.support.TransactionOperations;

public final class JdbcNoteBlockCreation implements NoteBlockCreation {
    private final JdbcOperations jdbc;
    private final TransactionOperations transaction;

    public JdbcNoteBlockCreation(JdbcOperations jdbc, TransactionOperations transaction) {
        this.jdbc = jdbc;
        this.transaction = transaction;
    }

    @Override
    public void create(long currentUserId, long blockedUserId, Supplier<Instant> createdAtAfterActiveMembers) {
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
            Instant createdAt = createdAtAfterActiveMembers.get();
            jdbc.update("""
                    INSERT INTO pawbridge_community.private_note_blocks(member_id, blocked_id, created_at)
                    VALUES (?, ?, ?) ON CONFLICT DO NOTHING
                    """, currentUserId, blockedUserId, Timestamp.from(createdAt));
        });
    }
}
