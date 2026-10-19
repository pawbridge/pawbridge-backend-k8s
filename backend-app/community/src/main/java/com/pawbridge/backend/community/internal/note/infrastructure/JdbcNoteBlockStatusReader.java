package com.pawbridge.backend.community.internal.note.infrastructure;

import com.pawbridge.backend.community.internal.note.application.NoteBlockStatusReader;
import java.util.Collections;
import java.util.List;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.transaction.support.TransactionOperations;

public final class JdbcNoteBlockStatusReader implements NoteBlockStatusReader {
    private final JdbcOperations jdbc;
    private final TransactionOperations readOnlyTransaction;

    public JdbcNoteBlockStatusReader(JdbcOperations jdbc, TransactionOperations readOnlyTransaction) {
        this.jdbc = jdbc;
        this.readOnlyTransaction = readOnlyTransaction;
    }

    @Override
    public List<Long> blockedAuthors(long currentUserId, List<Long> distinctMemberIds) {
        String placeholders = String.join(",", Collections.nCopies(distinctMemberIds.size(), "?"));
        Object[] parameters = new Object[distinctMemberIds.size() + 1];
        parameters[0] = currentUserId;
        for (int index = 0; index < distinctMemberIds.size(); index++) {
            parameters[index + 1] = distinctMemberIds.get(index);
        }
        return readOnlyTransaction.execute(status -> jdbc.query("""
                SELECT blocked_id FROM pawbridge_community.private_note_blocks
                WHERE member_id = ? AND blocked_id IN (%s) ORDER BY blocked_id
                """.formatted(placeholders), (row, number) -> row.getLong("blocked_id"), parameters));
    }
}
