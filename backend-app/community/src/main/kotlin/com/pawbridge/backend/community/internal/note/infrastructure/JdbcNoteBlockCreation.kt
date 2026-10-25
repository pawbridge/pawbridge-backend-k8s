package com.pawbridge.backend.community.internal.note.infrastructure

import com.pawbridge.backend.community.internal.note.application.NoteBlockCreation
import java.sql.Timestamp
import java.time.Instant
import java.util.function.Supplier
import java.util.stream.LongStream
import org.springframework.jdbc.core.JdbcOperations
import org.springframework.transaction.support.TransactionOperations

class JdbcNoteBlockCreation(
    private val jdbc: JdbcOperations,
    private val transaction: TransactionOperations,
) : NoteBlockCreation {

    override fun create(
        currentUserId: Long,
        blockedUserId: Long,
        createdAtAfterActiveMembers: Supplier<Instant>,
    ) {
        transaction.executeWithoutResult {
            for (memberId in LongStream.of(currentUserId, blockedUserId).distinct().sorted().toArray()) {
                jdbc.update(INSERT_MEMBER_SQL, memberId)
                jdbc.queryForObject(LOCK_MEMBER_SQL, Long::class.javaObjectType, memberId)
            }
            val createdAt = createdAtAfterActiveMembers.get()
            jdbc.update(INSERT_BLOCK_SQL, currentUserId, blockedUserId, Timestamp.from(createdAt))
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

        private val INSERT_BLOCK_SQL = """
            INSERT INTO pawbridge_community.private_note_blocks(member_id, blocked_id, created_at)
            VALUES (?, ?, ?) ON CONFLICT DO NOTHING
        """.trimIndent() + "\n"
    }
}
