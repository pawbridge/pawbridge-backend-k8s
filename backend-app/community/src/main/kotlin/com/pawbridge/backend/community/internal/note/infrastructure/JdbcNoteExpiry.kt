package com.pawbridge.backend.community.internal.note.infrastructure

import com.pawbridge.backend.community.internal.note.application.NoteExpiry
import java.sql.Timestamp
import java.time.Clock
import org.springframework.jdbc.core.JdbcOperations
import org.springframework.transaction.support.TransactionOperations

class JdbcNoteExpiry(
    private val jdbc: JdbcOperations,
    private val transaction: TransactionOperations,
    private val clock: Clock,
) : NoteExpiry {

    override fun expire(): Int =
        transaction.execute {
            val now = clock.instant()
            val removed = jdbc.update(DELETE_EXPIRED_NOTES_SQL, Timestamp.from(now))
            jdbc.update(DELETE_EXPIRED_REQUESTS_SQL, Timestamp.from(now.minusSeconds(172800)))
            removed
        }!!

    private companion object {
        private val DELETE_EXPIRED_NOTES_SQL = """
            WITH expired_notes AS MATERIALIZED (
                SELECT note_id FROM pawbridge_community.private_notes
                WHERE expires_at <= ? ORDER BY expires_at LIMIT 300 FOR UPDATE SKIP LOCKED
            )
            DELETE FROM pawbridge_community.private_notes
            WHERE note_id IN (SELECT note_id FROM expired_notes)
        """.trimIndent() + "\n"

        private val DELETE_EXPIRED_REQUESTS_SQL = """
            WITH expired_requests AS MATERIALIZED (
                SELECT sender_id, request_id FROM pawbridge_community.private_note_requests
                WHERE note_id IS NULL AND created_at < ?
                ORDER BY created_at LIMIT 300 FOR UPDATE SKIP LOCKED
            )
            DELETE FROM pawbridge_community.private_note_requests
            WHERE (sender_id, request_id) IN (SELECT sender_id, request_id FROM expired_requests)
        """.trimIndent() + "\n"
    }
}
