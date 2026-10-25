package com.pawbridge.backend.community.internal.note.infrastructure

import com.pawbridge.backend.community.internal.note.application.NoteBlockReader
import org.springframework.jdbc.core.JdbcOperations
import org.springframework.transaction.support.TransactionOperations

class JdbcNoteBlockReader(
    private val jdbc: JdbcOperations,
    private val readOnlyTransaction: TransactionOperations,
) : NoteBlockReader {

    override fun blocked(
        firstMemberId: Long,
        secondMemberId: Long,
    ): Boolean =
        readOnlyTransaction.execute {
            jdbc.queryForObject(
                BLOCKED_SQL,
                Boolean::class.javaObjectType,
                firstMemberId,
                secondMemberId,
                secondMemberId,
                firstMemberId,
            )
        }!!

    private companion object {
        private val BLOCKED_SQL = """
            SELECT EXISTS(
                SELECT 1 FROM pawbridge_community.private_note_blocks
                WHERE (member_id = ? AND blocked_id = ?) OR (member_id = ? AND blocked_id = ?)
            )
        """.trimIndent() + "\n"
    }
}
