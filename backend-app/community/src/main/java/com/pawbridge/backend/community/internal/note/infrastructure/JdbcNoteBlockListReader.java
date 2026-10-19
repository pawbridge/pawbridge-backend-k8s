package com.pawbridge.backend.community.internal.note.infrastructure;

import com.pawbridge.backend.community.internal.note.application.NoteBlockListReader;
import java.util.List;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.transaction.support.TransactionOperations;

public final class JdbcNoteBlockListReader implements NoteBlockListReader {
    private final JdbcOperations jdbc;
    private final TransactionOperations readOnlyTransaction;

    public JdbcNoteBlockListReader(JdbcOperations jdbc, TransactionOperations readOnlyTransaction) {
        this.jdbc = jdbc;
        this.readOnlyTransaction = readOnlyTransaction;
    }

    @Override
    public long count(long currentUserId) {
        return readOnlyTransaction.execute(status -> jdbc.queryForObject("""
                SELECT count(*) FROM pawbridge_community.private_note_blocks WHERE member_id = ?
                """, Long.class, currentUserId));
    }

    @Override
    public List<Block> read(long currentUserId, int page) {
        return readOnlyTransaction.execute(status -> jdbc.query("""
                SELECT blocked_id, created_at FROM pawbridge_community.private_note_blocks
                WHERE member_id = ? ORDER BY created_at DESC, blocked_id DESC LIMIT 10 OFFSET ?
                """, (row, rowNumber) -> new Block(row.getLong("blocked_id"), row.getTimestamp("created_at").toInstant()),
                currentUserId, (long) page * 10));
    }
}
