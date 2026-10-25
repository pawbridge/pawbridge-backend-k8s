package com.pawbridge.backend.community.internal.note.infrastructure

import com.pawbridge.backend.community.internal.note.application.NoteBlockStatusReader
import java.util.Collections
import org.springframework.jdbc.core.JdbcOperations
import org.springframework.jdbc.core.RowMapper
import org.springframework.transaction.support.TransactionOperations

class JdbcNoteBlockStatusReader(
    private val jdbc: JdbcOperations,
    private val readOnlyTransaction: TransactionOperations,
) : NoteBlockStatusReader {

    override fun blockedAuthors(
        currentUserId: Long,
        distinctMemberIds: List<Long>,
    ): List<Long> {
        val placeholders = Collections.nCopies(distinctMemberIds.size, "?").joinToString(",")
        val parameters = arrayOfNulls<Any>(distinctMemberIds.size + 1)
        parameters[0] = currentUserId
        for (index in distinctMemberIds.indices) {
            parameters[index + 1] = distinctMemberIds[index]
        }
        return readOnlyTransaction.execute {
            jdbc.query(
                BLOCKED_AUTHORS_SQL.format(placeholders),
                RowMapper { row, _ -> row.getLong("blocked_id") },
                *parameters,
            )
        }!!
    }

    private companion object {
        private val BLOCKED_AUTHORS_SQL = """
            SELECT blocked_id FROM pawbridge_community.private_note_blocks
            WHERE member_id = ? AND blocked_id IN (%s) ORDER BY blocked_id
        """.trimIndent() + "\n"
    }
}
