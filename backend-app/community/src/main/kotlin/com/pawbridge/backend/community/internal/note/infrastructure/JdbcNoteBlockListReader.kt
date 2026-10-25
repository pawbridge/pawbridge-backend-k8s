package com.pawbridge.backend.community.internal.note.infrastructure

import com.pawbridge.backend.community.internal.note.application.NoteBlockListReader
import com.pawbridge.backend.community.internal.note.application.NoteBlockListReader.Block
import org.springframework.jdbc.core.JdbcOperations
import org.springframework.jdbc.core.RowMapper
import org.springframework.transaction.support.TransactionOperations

class JdbcNoteBlockListReader(
    private val jdbc: JdbcOperations,
    private val readOnlyTransaction: TransactionOperations,
) : NoteBlockListReader {

    override fun count(currentUserId: Long): Long =
        readOnlyTransaction.execute {
            jdbc.queryForObject(COUNT_SQL, Long::class.javaObjectType, currentUserId)
        }!!

    override fun read(
        currentUserId: Long,
        page: Int,
    ): List<Block> =
        readOnlyTransaction.execute {
            jdbc.query(
                READ_SQL,
                RowMapper { row, _ -> Block(row.getLong("blocked_id"), row.getTimestamp("created_at").toInstant()) },
                currentUserId,
                page.toLong() * 10,
            )
        }!!

    private companion object {
        private val COUNT_SQL = """
            SELECT count(*) FROM pawbridge_community.private_note_blocks WHERE member_id = ?
        """.trimIndent() + "\n"

        private val READ_SQL = """
            SELECT blocked_id, created_at FROM pawbridge_community.private_note_blocks
            WHERE member_id = ? ORDER BY created_at DESC, blocked_id DESC LIMIT 10 OFFSET ?
        """.trimIndent() + "\n"
    }
}
