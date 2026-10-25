package com.pawbridge.backend.community.internal.note.infrastructure

import com.pawbridge.backend.community.internal.note.application.NoteBlockRemoval
import java.util.stream.LongStream
import org.springframework.jdbc.core.JdbcOperations
import org.springframework.transaction.support.TransactionOperations

class JdbcNoteBlockRemoval(
    private val jdbc: JdbcOperations,
    private val transaction: TransactionOperations,
) : NoteBlockRemoval {

    override fun remove(
        currentUserId: Long,
        blockedUserId: Long,
        requireActiveCurrent: Runnable,
    ) {
        transaction.executeWithoutResult {
            for (memberId in LongStream.of(currentUserId, blockedUserId).distinct().sorted().toArray()) {
                jdbc.update(INSERT_MEMBER_SQL, memberId)
                jdbc.queryForObject(LOCK_MEMBER_SQL, Long::class.javaObjectType, memberId)
            }
            requireActiveCurrent.run()
            jdbc.update(DELETE_BLOCK_SQL, currentUserId, blockedUserId)
        }
    }

    private companion object {
        private val INSERT_MEMBER_SQL = """
            INSERT INTO pawbridge_community.private_note_members(member_id)
            VALUES (?) ON CONFLICT DO NOTHING
        """.trimIndent() + "\n"

        private val LOCK_MEMBER_SQL = """
            SELECT member_id FROM pawbridge_community.private_note_members
            WHERE member_id = ? FOR UPDATE
        """.trimIndent() + "\n"

        private val DELETE_BLOCK_SQL = """
            DELETE FROM pawbridge_community.private_note_blocks WHERE member_id = ? AND blocked_id = ?
        """.trimIndent() + "\n"
    }
}
