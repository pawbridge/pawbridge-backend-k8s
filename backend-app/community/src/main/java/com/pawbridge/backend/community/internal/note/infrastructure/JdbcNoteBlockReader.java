package com.pawbridge.backend.community.internal.note.infrastructure;

import com.pawbridge.backend.community.internal.note.application.NoteBlockReader;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.transaction.support.TransactionOperations;

public final class JdbcNoteBlockReader implements NoteBlockReader {
    private static final String BLOCKED_SQL = """
            SELECT EXISTS(
                SELECT 1 FROM pawbridge_community.private_note_blocks
                WHERE (member_id = ? AND blocked_id = ?) OR (member_id = ? AND blocked_id = ?)
            )
            """;

    private final JdbcOperations jdbc;
    private final TransactionOperations readOnlyTransaction;

    public JdbcNoteBlockReader(JdbcOperations jdbc, TransactionOperations readOnlyTransaction) {
        this.jdbc = jdbc;
        this.readOnlyTransaction = readOnlyTransaction;
    }

    @Override
    public boolean blocked(long firstMemberId, long secondMemberId) {
        return readOnlyTransaction.execute(status -> jdbc.queryForObject(BLOCKED_SQL, Boolean.class,
                firstMemberId, secondMemberId, secondMemberId, firstMemberId));
    }
}
