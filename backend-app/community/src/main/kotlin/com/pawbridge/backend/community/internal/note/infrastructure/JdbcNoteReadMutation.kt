package com.pawbridge.backend.community.internal.note.infrastructure

import com.pawbridge.backend.community.internal.note.application.NoteChangeHints
import com.pawbridge.backend.community.internal.note.application.NoteReadMutation
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import java.util.function.Supplier
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.JdbcOperations
import org.springframework.jdbc.core.RowMapper
import org.springframework.transaction.support.TransactionOperations
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager

class JdbcNoteReadMutation(
    private val jdbc: JdbcOperations,
    private val transaction: TransactionOperations,
    private val hints: NoteChangeHints,
) : NoteReadMutation {

    override fun read(
        currentUserId: Long,
        noteId: UUID?,
        requireActive: Runnable,
        now: Supplier<Instant>,
    ) {
        transaction.executeWithoutResult {
            jdbc.update(INSERT_MEMBER_SQL, currentUserId)
            jdbc.queryForObject(LOCK_MEMBER_SQL, Long::class.javaObjectType, currentUserId)
            requireActive.run()
            requireOwned(currentUserId, noteId, now.get())
            // A concurrently removed note may yield no row; the second ownership check decides absence.
            jdbc.query(LOCK_NOTE_SQL, FOUND_ROW, noteId)
            requireOwned(currentUserId, noteId, now.get())
            jdbc.update(MARK_READ_SQL, Timestamp.from(now.get()), currentUserId, noteId)
            TransactionSynchronizationManager.registerSynchronization(
                object : TransactionSynchronization {
                    override fun afterCommit() {
                        try {
                            hints.resync(currentUserId)
                        } catch (failure: RuntimeException) {
                            log.warn(
                                "Private note push failed ({}); REST restoration remains available",
                                failure.javaClass.simpleName,
                            )
                        }
                    }
                },
            )
        }
    }

    private fun requireOwned(
        currentUserId: Long,
        noteId: UUID?,
        now: Instant,
    ) {
        val owned = jdbc.query(OWNED_NOTE_SQL, FOUND_ROW, currentUserId, noteId, Timestamp.from(now))
        if (owned.isEmpty()) {
            throw NoteReadMutation.MissingOwnedNote()
        }
    }

    private companion object {
        private val log = LoggerFactory.getLogger(JdbcNoteReadMutation::class.java)

        // Unused row parameters are declared nullable so tests may pass a null ResultSet, as with the Java lambda.
        private val FOUND_ROW = RowMapper<Boolean> { _: ResultSet?, _: Int -> true }

        private val INSERT_MEMBER_SQL = """
            INSERT INTO pawbridge_community.private_note_members(member_id)
            VALUES (?) ON CONFLICT DO NOTHING
        """.trimIndent() + "\n"

        private val LOCK_MEMBER_SQL = """
            SELECT member_id FROM pawbridge_community.private_note_members
            WHERE member_id = ? FOR UPDATE
        """.trimIndent() + "\n"

        private val LOCK_NOTE_SQL = """
            SELECT note_id FROM pawbridge_community.private_notes
            WHERE note_id = ? FOR UPDATE
        """.trimIndent() + "\n"

        private val MARK_READ_SQL = """
            UPDATE pawbridge_community.private_note_mailboxes SET read_at = COALESCE(read_at, ?)
            WHERE member_id = ? AND note_id = ? AND direction = 'INBOX'
        """.trimIndent() + "\n"

        private val OWNED_NOTE_SQL = """
            SELECT 1 FROM pawbridge_community.private_note_mailboxes m
            JOIN pawbridge_community.private_notes n ON n.note_id = m.note_id
            WHERE m.member_id = ? AND n.note_id = ? AND n.expires_at > ?
        """.trimIndent() + "\n"
    }
}
