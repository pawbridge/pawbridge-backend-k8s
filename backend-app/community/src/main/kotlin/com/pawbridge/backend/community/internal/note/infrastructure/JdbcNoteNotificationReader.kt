package com.pawbridge.backend.community.internal.note.infrastructure

import com.pawbridge.backend.community.internal.note.application.NoteNotificationReader
import com.pawbridge.backend.community.internal.note.application.NoteNotificationReader.Row
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import org.springframework.jdbc.core.JdbcOperations
import org.springframework.jdbc.core.RowMapper
import org.springframework.transaction.support.TransactionOperations

class JdbcNoteNotificationReader(
    private val jdbc: JdbcOperations,
    private val readOnlyTransaction: TransactionOperations,
) : NoteNotificationReader {

    override fun read(
        currentUserId: Long,
        cursor: UUID?,
        now: Instant,
    ): List<Row> =
        readOnlyTransaction.execute {
            if (cursor == null) {
                jdbc.query(
                    FIRST_PAGE_SQL,
                    RowMapper { row, _ -> mapRow(row) },
                    currentUserId,
                    Timestamp.from(now),
                )
            } else {
                jdbc.query(
                    NEXT_PAGE_SQL,
                    RowMapper { row, _ -> mapRow(row) },
                    currentUserId,
                    Timestamp.from(now),
                    currentUserId,
                    cursor,
                )
            }
        }!!

    override fun unread(
        currentUserId: Long,
        now: Instant,
    ): Long =
        readOnlyTransaction.execute {
            jdbc.queryForObject(UNREAD_SQL, Long::class.javaObjectType, currentUserId, Timestamp.from(now))
        }!!

    private fun mapRow(row: ResultSet): Row {
        val readAt = row.getTimestamp("read_at")
        return Row(
            row.getObject("note_id", UUID::class.java),
            row.getObject("sender_id") as Long?,
            row.getTimestamp("created_at").toInstant(),
            readAt?.toInstant(),
        )
    }

    private companion object {
        // The closing delimiter of the Java text block is indented less than its lines,
        // so the join keeps one leading space on each line.
        private val MAILBOX_JOIN = """
            | FROM pawbridge_community.private_note_mailboxes m
            | JOIN pawbridge_community.private_notes n ON n.note_id = m.note_id
        """.trimMargin() + "\n"

        private val FIRST_PAGE_SQL = "SELECT n.note_id, n.sender_id, n.created_at, m.read_at" + MAILBOX_JOIN + """
            WHERE m.member_id = ? AND m.direction = 'INBOX' AND n.expires_at > ?
            ORDER BY m.created_at DESC, m.note_id DESC LIMIT 21
        """.trimIndent() + "\n"

        private val NEXT_PAGE_SQL = "SELECT n.note_id, n.sender_id, n.created_at, m.read_at" + MAILBOX_JOIN + """
            WHERE m.member_id = ? AND m.direction = 'INBOX' AND n.expires_at > ?
              AND (m.created_at, m.note_id) < (
                  SELECT created_at, note_id FROM pawbridge_community.private_note_mailboxes
                  WHERE member_id = ? AND note_id = ? AND direction = 'INBOX'
              )
            ORDER BY m.created_at DESC, m.note_id DESC LIMIT 21
        """.trimIndent() + "\n"

        private val UNREAD_SQL = "SELECT count(*)" + MAILBOX_JOIN + """
            WHERE m.member_id = ? AND m.direction = 'INBOX' AND m.read_at IS NULL AND n.expires_at > ?
        """.trimIndent() + "\n"
    }
}
