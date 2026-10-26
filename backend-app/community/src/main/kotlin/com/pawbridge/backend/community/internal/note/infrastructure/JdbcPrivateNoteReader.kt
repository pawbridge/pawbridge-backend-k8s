package com.pawbridge.backend.community.internal.note.infrastructure

import com.pawbridge.backend.community.internal.note.application.PrivateNoteReader
import com.pawbridge.backend.community.internal.note.application.PrivateNoteReader.StoredNote
import java.sql.Timestamp
import java.time.Instant
import java.util.Optional
import java.util.UUID
import org.springframework.jdbc.core.JdbcOperations
import org.springframework.jdbc.core.RowMapper
import org.springframework.transaction.support.TransactionOperations

class JdbcPrivateNoteReader(
    private val jdbc: JdbcOperations,
    private val readOnlyTransaction: TransactionOperations,
) : PrivateNoteReader {

    override fun find(
        currentUserId: Long,
        noteId: UUID?,
        now: Instant,
    ): Optional<StoredNote> =
        readOnlyTransaction.execute {
            jdbc.query(
                FIND_SQL,
                RowMapper { row, _ ->
                    val readAt = row.getTimestamp("read_at")
                    StoredNote(
                        row.getObject("note_id", UUID::class.java),
                        row.getObject("sender_id") as Long?,
                        row.getObject("recipient_id") as Long?,
                        row.getString("body"),
                        row.getString("context_type"),
                        row.getObject("context_id") as Long?,
                        row.getTimestamp("created_at").toInstant(),
                        row.getString("direction"),
                        readAt?.toInstant(),
                        row.getBoolean("favorite"),
                    )
                },
                currentUserId,
                noteId,
                Timestamp.from(now),
            ).stream().findFirst()
        }!!

    private companion object {
        private val FIND_SQL = """
            SELECT n.note_id, n.sender_id, n.recipient_id, n.body, n.context_type, n.context_id,
                   n.created_at, m.direction, m.read_at, m.favorite
            FROM pawbridge_community.private_note_mailboxes m
            JOIN pawbridge_community.private_notes n ON n.note_id = m.note_id
            WHERE m.member_id = ? AND n.note_id = ? AND n.expires_at > ?
        """.trimIndent() + "\n"
    }
}
