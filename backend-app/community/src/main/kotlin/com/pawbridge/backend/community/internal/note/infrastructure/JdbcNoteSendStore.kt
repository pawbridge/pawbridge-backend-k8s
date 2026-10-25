package com.pawbridge.backend.community.internal.note.infrastructure

import com.pawbridge.backend.community.internal.note.application.NoteDeliveryHints
import com.pawbridge.backend.community.internal.note.application.NoteNotifications.Notification
import com.pawbridge.backend.community.internal.note.application.NoteSendStore
import com.pawbridge.backend.community.internal.note.application.NoteSendStore.NewNote
import com.pawbridge.backend.community.internal.note.application.NoteSendStore.PreviousRequest
import com.pawbridge.backend.community.internal.note.application.NoteSendStore.Reply
import com.pawbridge.backend.community.internal.note.application.SendPrivateNote.Receipt
import java.sql.Timestamp
import java.time.Instant
import java.util.Optional
import java.util.UUID
import java.util.function.Supplier
import java.util.stream.LongStream
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.JdbcOperations
import org.springframework.jdbc.core.RowMapper
import org.springframework.transaction.support.TransactionOperations
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager

class JdbcNoteSendStore(
    private val jdbc: JdbcOperations,
    private val transaction: TransactionOperations,
    private val hints: NoteDeliveryHints,
) : NoteSendStore {

    override fun inTransaction(action: Supplier<Receipt>): Receipt =
        transaction.execute { action.get() }!!

    override fun lockMembers(
        firstMemberId: Long,
        secondMemberId: Long,
    ) {
        for (memberId in LongStream.of(firstMemberId, secondMemberId).distinct().sorted().toArray()) {
            jdbc.update(INSERT_MEMBER_SQL, memberId)
            jdbc.queryForObject(LOCK_MEMBER_SQL, Long::class.javaObjectType, memberId)
        }
    }

    override fun request(
        senderId: Long,
        requestId: UUID,
    ): Optional<PreviousRequest> =
        jdbc.query(
            REQUEST_SQL,
            RowMapper { row, _ -> PreviousRequest(row.getObject("note_id", UUID::class.java), row.getString("request_hash")) },
            senderId,
            requestId,
        ).stream().findFirst()

    override fun blocked(
        firstMemberId: Long,
        secondMemberId: Long,
    ): Boolean =
        jdbc.queryForObject(
            BLOCKED_SQL,
            Boolean::class.javaObjectType,
            firstMemberId,
            secondMemberId,
            secondMemberId,
            firstMemberId,
        )!!

    override fun rate(
        senderId: Long,
        since: Instant,
    ): Long =
        jdbc.queryForObject(RATE_SQL, Long::class.javaObjectType, senderId, Timestamp.from(since))!!

    override fun reply(
        ownerId: Long,
        noteId: UUID,
        now: Instant,
    ): Optional<Reply> =
        jdbc.query(
            REPLY_SQL,
            RowMapper { row, _ -> Reply(row.getString("direction"), row.getObject("sender_id") as Long?) },
            ownerId,
            noteId,
            Timestamp.from(now),
        ).stream().findFirst()

    override fun contextAuthor(
        contextType: String,
        contextId: Long,
        recipientId: Long,
    ): Boolean {
        // Table names come only from the application's validated POST/REPORT values.
        val table = if ("POST" == contextType) "posts" else "animal_reports"
        val idColumn = if ("POST" == contextType) "post_id" else "report_id"
        val sql = CONTEXT_AUTHOR_SQL.format(table, idColumn)
        return jdbc.queryForObject(sql, Boolean::class.javaObjectType, contextId, recipientId)!!
    }

    override fun insert(
        note: NewNote,
        notification: Notification,
    ) {
        jdbc.update(
            INSERT_NOTE_SQL,
            note.noteId,
            note.senderId,
            note.recipientId,
            note.body,
            note.replyTo,
            note.contextType,
            note.contextId,
            Timestamp.from(note.createdAt),
            Timestamp.from(note.expiresAt),
        )
        jdbc.update(
            INSERT_MAILBOXES_SQL,
            note.senderId,
            note.noteId,
            Timestamp.from(note.createdAt),
            Timestamp.from(note.createdAt),
            note.recipientId,
            note.noteId,
            Timestamp.from(note.createdAt),
        )
        jdbc.update(
            INSERT_REQUEST_SQL,
            note.senderId,
            note.requestId,
            note.requestHash,
            note.noteId,
            Timestamp.from(note.createdAt),
        )
        TransactionSynchronizationManager.registerSynchronization(
            object : TransactionSynchronization {
                override fun afterCommit() {
                    try {
                        hints.publish(note.recipientId, notification)
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

    private companion object {
        private val log = LoggerFactory.getLogger(JdbcNoteSendStore::class.java)

        private val INSERT_MEMBER_SQL = """
            INSERT INTO pawbridge_community.private_note_members(member_id)
            VALUES (?) ON CONFLICT DO NOTHING
        """.trimIndent() + "\n"

        private val LOCK_MEMBER_SQL = """
            SELECT member_id FROM pawbridge_community.private_note_members
            WHERE member_id = ? FOR UPDATE
        """.trimIndent() + "\n"

        private val REQUEST_SQL = """
            SELECT note_id, request_hash FROM pawbridge_community.private_note_requests
            WHERE sender_id = ? AND request_id = ?
        """.trimIndent() + "\n"

        private val BLOCKED_SQL = """
            SELECT EXISTS(
                SELECT 1 FROM pawbridge_community.private_note_blocks
                WHERE (member_id = ? AND blocked_id = ?) OR (member_id = ? AND blocked_id = ?)
            )
        """.trimIndent() + "\n"

        private val RATE_SQL = """
            SELECT count(*) FROM pawbridge_community.private_note_requests
            WHERE sender_id = ? AND created_at >= ?
        """.trimIndent() + "\n"

        private val REPLY_SQL = """
            SELECT m.direction, n.sender_id
            FROM pawbridge_community.private_note_mailboxes m
            JOIN pawbridge_community.private_notes n ON n.note_id = m.note_id
            WHERE m.member_id = ? AND n.note_id = ? AND n.expires_at > ?
        """.trimIndent() + "\n"

        private val CONTEXT_AUTHOR_SQL = """
            SELECT EXISTS(
                SELECT 1 FROM pawbridge_community.%s
                WHERE %s = ? AND author_id = ? AND deleted_at IS NULL
            )
        """.trimIndent() + "\n"

        private val INSERT_NOTE_SQL = """
            INSERT INTO pawbridge_community.private_notes(
                note_id, sender_id, recipient_id, body, reply_to,
                context_type, context_id, created_at, expires_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
        """.trimIndent() + "\n"

        private val INSERT_MAILBOXES_SQL = """
            INSERT INTO pawbridge_community.private_note_mailboxes(
                member_id, note_id, direction, read_at, created_at
            ) VALUES (?, ?, 'SENT', ?, ?), (?, ?, 'INBOX', NULL, ?)
        """.trimIndent() + "\n"

        private val INSERT_REQUEST_SQL = """
            INSERT INTO pawbridge_community.private_note_requests(
                sender_id, request_id, request_hash, note_id, created_at
            ) VALUES (?, ?, ?, ?, ?)
        """.trimIndent() + "\n"
    }
}
