package com.pawbridge.backend.community.internal.note.infrastructure

import com.pawbridge.backend.community.internal.note.application.ContactMemberCleanupStore
import java.util.Collections
import java.util.UUID
import java.util.function.LongConsumer
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.JdbcOperations
import org.springframework.jdbc.core.RowMapper
import org.springframework.transaction.support.TransactionOperations
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager

class JdbcContactMemberCleanupStore(
    private val jdbc: JdbcOperations,
    private val transaction: TransactionOperations,
    private val committedCleanup: LongConsumer,
) : ContactMemberCleanupStore {

    override fun remove(
        memberId: Long,
        requireDeletionPending: Runnable,
    ) {
        transaction.executeWithoutResult {
            jdbc.update(INSERT_MEMBER_SQL, memberId)
            jdbc.queryForObject(LOCK_MEMBER_SQL, Long::class.javaObjectType, memberId)
            requireDeletionPending.run()
            withdrawNotes(memberId)
            if (jdbc.queryForObject(CHAT_INSTALLED_SQL, Boolean::class.javaObjectType)!!) {
                withdrawChat(memberId)
            }
            TransactionSynchronizationManager.registerSynchronization(
                object : TransactionSynchronization {
                    override fun afterCommit() {
                        try {
                            committedCleanup.accept(memberId)
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

    private fun withdrawNotes(memberId: Long) {
        val affectedNotes: List<UUID> = jdbc.query(
            AFFECTED_NOTES_SQL,
            RowMapper { row, _ -> row.getObject("note_id", UUID::class.java) },
            memberId,
            memberId,
        )
        jdbc.update(CLEAR_REQUEST_HASH_SQL, memberId, memberId)
        jdbc.update(CLEAR_CONTEXT_SQL, memberId, memberId)
        jdbc.update(DELETE_MEMBER_SQL, memberId)
        var start = 0
        while (start < affectedNotes.size) {
            val batch = affectedNotes.subList(start, Math.min(start + CLEANUP_BATCH_SIZE, affectedNotes.size))
            val placeholders = Collections.nCopies(batch.size, "?").joinToString(",")
            val sql = DELETE_ORPHAN_NOTES_SQL.format(placeholders)
            jdbc.update(sql, *batch.toTypedArray<Any?>())
            start += CLEANUP_BATCH_SIZE
        }
    }

    private fun withdrawChat(memberId: Long) {
        val rooms: List<UUID> = jdbc.queryForList(CHAT_ROOMS_SQL, UUID::class.java, memberId)
        jdbc.update(DELETE_CHAT_ROOM_MEMBER_SQL, memberId)
        jdbc.update(DELETE_CHAT_REQUESTS_SQL, memberId)
        jdbc.update(CLEAR_CHAT_MESSAGE_SENDER_SQL, memberId)
        jdbc.update(CLEAR_FIRST_MEMBER_SQL, memberId)
        jdbc.update(CLEAR_SECOND_MEMBER_SQL, memberId)
        for (room in rooms) {
            jdbc.update(DELETE_EMPTY_ROOM_SQL, room)
        }
    }

    private companion object {
        private val log = LoggerFactory.getLogger(JdbcContactMemberCleanupStore::class.java)
        private const val CLEANUP_BATCH_SIZE = 300

        private val INSERT_MEMBER_SQL = """
            INSERT INTO pawbridge_community.private_note_members(member_id)
            VALUES (?) ON CONFLICT DO NOTHING
        """.trimIndent() + "\n"

        private val LOCK_MEMBER_SQL = """
            SELECT member_id FROM pawbridge_community.private_note_members
            WHERE member_id = ? FOR UPDATE
        """.trimIndent() + "\n"

        private const val CHAT_INSTALLED_SQL = "SELECT to_regclass('pawbridge_community.member_chat_rooms') IS NOT NULL"

        private val AFFECTED_NOTES_SQL = """
            SELECT note_id FROM pawbridge_community.private_notes
            WHERE sender_id = ? OR recipient_id = ? ORDER BY note_id FOR UPDATE
        """.trimIndent() + "\n"

        private val CLEAR_REQUEST_HASH_SQL = """
            UPDATE pawbridge_community.private_note_requests SET request_hash = NULL
            WHERE note_id IN (
                SELECT note_id FROM pawbridge_community.private_notes WHERE sender_id = ? OR recipient_id = ?
            )
        """.trimIndent() + "\n"

        private val CLEAR_CONTEXT_SQL = """
            UPDATE pawbridge_community.private_notes SET context_type = NULL, context_id = NULL
            WHERE sender_id = ? OR recipient_id = ?
        """.trimIndent() + "\n"

        private const val DELETE_MEMBER_SQL = "DELETE FROM pawbridge_community.private_note_members WHERE member_id = ?"

        private val DELETE_ORPHAN_NOTES_SQL = """
            DELETE FROM pawbridge_community.private_notes n
            WHERE note_id IN (%s) AND NOT EXISTS(
                SELECT 1 FROM pawbridge_community.private_note_mailboxes m WHERE m.note_id = n.note_id
            )
        """.trimIndent() + "\n"

        private const val CHAT_ROOMS_SQL =
            "SELECT room_id FROM pawbridge_community.member_chat_room_members WHERE member_id=?"
        private const val DELETE_CHAT_ROOM_MEMBER_SQL =
            "DELETE FROM pawbridge_community.member_chat_room_members WHERE member_id=?"
        private const val DELETE_CHAT_REQUESTS_SQL =
            "DELETE FROM pawbridge_community.member_chat_requests WHERE sender_id=?"
        private const val CLEAR_CHAT_MESSAGE_SENDER_SQL =
            "UPDATE pawbridge_community.member_chat_messages SET sender_id=NULL WHERE sender_id=?"
        private const val CLEAR_FIRST_MEMBER_SQL =
            "UPDATE pawbridge_community.member_chat_rooms SET first_member_id=NULL WHERE first_member_id=?"
        private const val CLEAR_SECOND_MEMBER_SQL =
            "UPDATE pawbridge_community.member_chat_rooms SET second_member_id=NULL WHERE second_member_id=?"

        private val DELETE_EMPTY_ROOM_SQL = """
            DELETE FROM pawbridge_community.member_chat_rooms r WHERE room_id=? AND NOT EXISTS(
              SELECT 1 FROM pawbridge_community.member_chat_room_members a WHERE a.room_id=r.room_id)
        """.trimIndent() + "\n"
    }
}
