package com.pawbridge.backend.community.internal.note.infrastructure

import com.pawbridge.backend.community.internal.note.application.PrivateNoteListReader
import com.pawbridge.backend.community.internal.note.application.PrivateNoteReader.StoredNote
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import org.springframework.jdbc.core.JdbcOperations
import org.springframework.jdbc.core.RowMapper
import org.springframework.transaction.support.TransactionOperations

class JdbcPrivateNoteListReader(
    private val jdbc: JdbcOperations,
    private val readOnlyTransaction: TransactionOperations,
) : PrivateNoteListReader {

    override fun count(
        currentUserId: Long,
        box: String,
        favorites: Boolean,
        now: Instant,
    ): Long =
        readOnlyTransaction.execute {
            jdbc.queryForObject(
                COUNT_SQL,
                Long::class.javaObjectType,
                currentUserId,
                box,
                favorites,
                Timestamp.from(now),
            )
        }!!

    override fun read(
        currentUserId: Long,
        box: String,
        favorites: Boolean,
        page: Int,
        now: Instant,
    ): List<StoredNote> =
        readOnlyTransaction.execute {
            jdbc.query(
                READ_SQL,
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
                box,
                favorites,
                Timestamp.from(now),
                page.toLong() * 10,
            )
        }!!

    private companion object {
        private val COUNT_SQL = """
            SELECT count(*)
            FROM pawbridge_community.private_note_mailboxes m
            JOIN pawbridge_community.private_notes n ON n.note_id = m.note_id
            WHERE m.member_id = ? AND m.direction = ? AND (NOT ? OR m.favorite) AND n.expires_at > ?
        """.trimIndent() + "\n"

        private val READ_SQL = """
            SELECT n.note_id, n.sender_id, n.recipient_id, n.body, n.context_type, n.context_id,
                   n.created_at, m.direction, m.read_at, m.favorite
            FROM pawbridge_community.private_note_mailboxes m
            JOIN pawbridge_community.private_notes n ON n.note_id = m.note_id
            WHERE m.member_id = ? AND m.direction = ? AND (NOT ? OR m.favorite) AND n.expires_at > ?
            ORDER BY m.created_at DESC, m.note_id DESC LIMIT 10 OFFSET ?
        """.trimIndent() + "\n"
    }
}
