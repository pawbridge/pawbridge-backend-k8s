package com.pawbridge.backend.community.internal.chat.infrastructure

import com.pawbridge.backend.community.internal.chat.application.MemberChatModels.Message
import com.pawbridge.backend.community.internal.chat.application.MemberChatModels.Receipt
import com.pawbridge.backend.community.internal.chat.application.MemberChatModels.Send
import java.sql.Timestamp
import java.time.Instant
import java.time.ZoneOffset
import java.util.Optional
import java.util.UUID
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper

open class JdbcMemberChatRepository(
    private val jdbc: JdbcTemplate,
) {

    @JvmRecord
    data class SavedRequest(
        val hash: String?,
        val receipt: Receipt,
        val retained: Boolean,
    )

    @JvmRecord
    data class Membership(
        val roomId: UUID?,
        val counterpartId: Long?,
        val latestSequence: Long,
        val readThrough: Long,
        val counterpartReadThrough: Long,
        val hiddenThrough: Long,
    )

    @JvmRecord
    data class Summary(
        val membership: Membership,
        val updatedAt: Instant?,
        val preview: String?,
        val unread: Boolean,
    )

    open fun installed(): Boolean =
        jdbc.queryForObject(INSTALLED_SQL, Boolean::class.javaObjectType)!!

    open fun request(
        senderId: Long,
        requestId: UUID,
        now: Instant,
    ): Optional<SavedRequest> =
        jdbc.query(
            REQUEST_SQL,
            RowMapper { row, _ ->
                SavedRequest(
                    row.getString("request_hash"),
                    Receipt(requestId, row.getObject("room_id", UUID::class.java), row.getLong("sequence")),
                    row.getBoolean("retained"),
                )
            },
            Timestamp.from(now),
            senderId,
            requestId,
        ).stream().findFirst()

    open fun rate(
        senderId: Long,
        since: Instant,
    ): Long =
        jdbc.queryForObject(RATE_SQL, Long::class.javaObjectType, senderId, Timestamp.from(since))!!

    open fun roomForSend(
        sender: Long,
        recipient: Long,
    ): UUID? {
        val first = Math.min(sender, recipient)
        val second = Math.max(sender, recipient)
        val proposed = UUID.randomUUID()
        jdbc.update(INSERT_ROOM_SQL, proposed, first, second)
        val room: UUID? = jdbc.queryForObject(LOCK_ROOM_SQL, UUID::class.java, first, second)
        jdbc.update(INSERT_ROOM_MEMBERS_SQL, room, first, room, second)
        return room
    }

    open fun existingRoom(
        owner: Long,
        counterpartId: Long,
    ): Optional<UUID> =
        jdbc.query(
            EXISTING_ROOM_SQL,
            RowMapper { row, _ -> row.getObject("room_id", UUID::class.java) },
            owner,
            counterpartId,
            Math.min(owner, counterpartId),
            Math.max(owner, counterpartId),
        ).stream().findFirst()

    open fun membership(
        memberId: Long,
        roomId: UUID?,
    ): Optional<Membership> =
        jdbc.query(
            MEMBERSHIP_SQL,
            RowMapper { row, _ ->
                Membership(
                    roomId,
                    row.getObject("counterpart_id", Long::class.javaObjectType),
                    row.getLong("latest_sequence"),
                    row.getLong("read_through"),
                    row.getLong("counterpart_read_through"),
                    row.getLong("hidden_through"),
                )
            },
            memberId,
            roomId,
        ).stream().findFirst()

    open fun insert(
        sender: Long,
        input: Send,
        roomId: UUID?,
        hash: String,
        now: Instant,
        expires: Instant,
    ): Receipt {
        val sequence: Long = jdbc.queryForObject(NEXT_SEQUENCE_SQL, Long::class.javaObjectType, roomId)!!
        jdbc.update(
            INSERT_MESSAGE_SQL,
            roomId,
            sequence,
            sender,
            input.body,
            input.contextType,
            input.contextId,
            Timestamp.from(now),
            Timestamp.from(expires),
        )
        jdbc.update(
            INSERT_REQUEST_SQL,
            sender,
            input.requestId,
            hash,
            roomId,
            sequence,
            Timestamp.from(now),
        )
        return Receipt(input.requestId, roomId, sequence)
    }

    open fun messages(
        room: UUID?,
        before: Long?,
        after: Long?,
        now: Instant,
    ): List<Message> =
        jdbc.query(
            MESSAGES_SQL,
            RowMapper { row, _ ->
                Message(
                    room,
                    row.getLong("sequence"),
                    row.getObject("sender_id", Long::class.javaObjectType),
                    row.getString("body"),
                    if (row.getString("context_type") == null) {
                        null
                    } else {
                        (if ("POST" == row.getString("context_type")) "/community/" else "/reports/") +
                            row.getLong("context_id")
                    },
                    row.getTimestamp("created_at").toInstant(),
                )
            },
            room,
            Timestamp.from(now),
            before,
            before,
            after,
            after,
            after,
        )

    open fun latest(
        room: UUID?,
        now: Instant,
    ): Optional<Message> =
        jdbc.query(
            LATEST_SQL,
            RowMapper { row, _ ->
                Message(
                    room,
                    row.getLong("sequence"),
                    row.getObject("sender_id", Long::class.javaObjectType),
                    row.getString("body"),
                    null,
                    row.getTimestamp("created_at").toInstant(),
                )
            },
            room,
            Timestamp.from(now),
        ).stream().findFirst()

    open fun rooms(
        owner: Long,
        cursor: UUID?,
        unreadOnly: Boolean,
        now: Instant,
    ): List<Summary> =
        jdbc.query(
            ROOMS_SQL,
            RowMapper { row, _ ->
                Summary(
                    Membership(
                        row.getObject("room_id", UUID::class.java),
                        row.getObject("counterpart_id", Long::class.javaObjectType),
                        row.getLong("latest_sequence"),
                        row.getLong("read_through"),
                        row.getLong("counterpart_read_through"),
                        row.getLong("hidden_through"),
                    ),
                    row.getTimestamp("updated_at").toInstant(),
                    row.getString("preview"),
                    row.getBoolean("unread"),
                )
            },
            owner,
            Timestamp.from(now),
            Timestamp.from(now),
            owner,
            unreadOnly,
            cursor,
            cursor,
        )

    open fun unreadRooms(
        owner: Long,
        now: Instant,
    ): Long =
        jdbc.queryForObject(UNREAD_ROOMS_SQL, Long::class.javaObjectType, owner, owner, Timestamp.from(now))!!

    open fun unreadRoom(
        owner: Long,
        room: UUID?,
        now: Instant,
    ): Boolean =
        jdbc.queryForObject(UNREAD_ROOM_SQL, Boolean::class.javaObjectType, owner, room, owner, Timestamp.from(now))!!

    open fun read(
        owner: Long,
        room: UUID?,
        through: Long,
    ) {
        jdbc.update(READ_SQL, through, owner, room)
    }

    open fun hide(
        owner: Long,
        room: UUID?,
    ) {
        jdbc.update(HIDE_SQL, owner, room)
    }

    open fun withdraw(memberId: Long) {
        val rooms: List<UUID> = jdbc.queryForList(WITHDRAW_ROOMS_SQL, UUID::class.java, memberId)
        jdbc.update(WITHDRAW_ROOM_MEMBERS_SQL, memberId)
        jdbc.update(WITHDRAW_REQUESTS_SQL, memberId)
        jdbc.update(WITHDRAW_MESSAGES_SQL, memberId)
        jdbc.update(WITHDRAW_FIRST_MEMBER_SQL, memberId)
        jdbc.update(WITHDRAW_SECOND_MEMBER_SQL, memberId)
        for (room in rooms) {
            jdbc.update(DELETE_EMPTY_ROOM_SQL, room)
        }
    }

    open fun expire(now: Instant) {
        jdbc.update(EXPIRE_MESSAGES_SQL, Timestamp.from(now))
        jdbc.update(
            EXPIRE_REQUESTS_SQL,
            Timestamp.from(now.atZone(ZoneOffset.UTC).minusYears(1).toInstant()),
        )
    }

    private companion object {
        private const val INSTALLED_SQL = "SELECT to_regclass('pawbridge_community.member_chat_rooms') IS NOT NULL"

        private val REQUEST_SQL = """
            SELECT q.*, EXISTS(SELECT 1 FROM pawbridge_community.member_chat_messages m
              WHERE m.room_id=q.room_id AND m.sequence=q.sequence AND m.expires_at>?) AS retained
            FROM pawbridge_community.member_chat_requests q WHERE sender_id=? AND request_id=?
        """.trimIndent() + "\n"

        private val RATE_SQL = """
            SELECT count(*) FROM pawbridge_community.member_chat_requests WHERE sender_id=? AND created_at>=?
        """.trimIndent() + "\n"

        private val INSERT_ROOM_SQL = """
            INSERT INTO pawbridge_community.member_chat_rooms(room_id,first_member_id,second_member_id)
            VALUES (?,?,?) ON CONFLICT (first_member_id,second_member_id) DO NOTHING
        """.trimIndent() + "\n"

        private val LOCK_ROOM_SQL = """
            SELECT room_id FROM pawbridge_community.member_chat_rooms
            WHERE first_member_id=? AND second_member_id=? FOR UPDATE
        """.trimIndent() + "\n"

        private val INSERT_ROOM_MEMBERS_SQL = """
            INSERT INTO pawbridge_community.member_chat_room_members(room_id,member_id)
            VALUES (?,?),(?,?) ON CONFLICT DO NOTHING
        """.trimIndent() + "\n"

        private val EXISTING_ROOM_SQL = """
            SELECT r.room_id FROM pawbridge_community.member_chat_rooms r
            JOIN pawbridge_community.member_chat_room_members owner
              ON owner.room_id=r.room_id AND owner.member_id=?
            JOIN pawbridge_community.member_chat_room_members counterpart
              ON counterpart.room_id=r.room_id AND counterpart.member_id=?
            WHERE r.first_member_id=? AND r.second_member_id=?
        """.trimIndent() + "\n"

        private val MEMBERSHIP_SQL = """
            SELECT r.*, a.read_through, a.hidden_through, b.member_id AS counterpart_id,
                   coalesce(b.read_through,0) AS counterpart_read_through
            FROM pawbridge_community.member_chat_rooms r
            JOIN pawbridge_community.member_chat_room_members a ON a.room_id=r.room_id AND a.member_id=?
            LEFT JOIN pawbridge_community.member_chat_room_members b ON b.room_id=r.room_id AND b.member_id<>a.member_id
            WHERE r.room_id=?
        """.trimIndent() + "\n"

        private val NEXT_SEQUENCE_SQL = """
            UPDATE pawbridge_community.member_chat_rooms SET latest_sequence=latest_sequence+1
            WHERE room_id=? RETURNING latest_sequence
        """.trimIndent() + "\n"

        private val INSERT_MESSAGE_SQL = """
            INSERT INTO pawbridge_community.member_chat_messages
            (room_id,sequence,sender_id,body,context_type,context_id,created_at,expires_at)
            VALUES (?,?,?,?,?,?,?,?)
        """.trimIndent() + "\n"

        private val INSERT_REQUEST_SQL = """
            INSERT INTO pawbridge_community.member_chat_requests
            (sender_id,request_id,request_hash,room_id,sequence,created_at) VALUES (?,?,?,?,?,?)
        """.trimIndent() + "\n"

        private val MESSAGES_SQL = """
            SELECT * FROM pawbridge_community.member_chat_messages
            WHERE room_id=? AND expires_at>? AND (?::bigint IS NULL OR sequence<?)
            AND (?::bigint IS NULL OR sequence>?)
            ORDER BY CASE WHEN ?::bigint IS NOT NULL THEN sequence END ASC, sequence DESC LIMIT 51
        """.trimIndent() + "\n"

        private val LATEST_SQL = """
            SELECT room_id,sequence,sender_id,body,created_at
            FROM pawbridge_community.member_chat_messages
            WHERE room_id=? AND expires_at>? ORDER BY sequence DESC LIMIT 1
        """.trimIndent() + "\n"

        private val ROOMS_SQL = """
            WITH visible AS (
              SELECT r.room_id, r.latest_sequence, a.read_through, a.hidden_through,
                     b.member_id AS counterpart_id, coalesce(b.read_through,0) AS counterpart_read_through,
                     last.created_at AS updated_at, last.body AS preview,
                     EXISTS(SELECT 1 FROM pawbridge_community.member_chat_messages x
                       WHERE x.room_id=r.room_id AND x.sequence>a.read_through
                         AND x.sender_id IS DISTINCT FROM ? AND x.expires_at>?) AS unread
              FROM pawbridge_community.member_chat_room_members a
              JOIN pawbridge_community.member_chat_rooms r ON r.room_id=a.room_id
              LEFT JOIN pawbridge_community.member_chat_room_members b ON b.room_id=r.room_id AND b.member_id<>a.member_id
              JOIN LATERAL (SELECT body, created_at FROM pawbridge_community.member_chat_messages m
                 WHERE m.room_id=r.room_id AND m.expires_at>? ORDER BY sequence DESC LIMIT 1) last ON true
              WHERE a.member_id=? AND r.latest_sequence>a.hidden_through
            )
            SELECT * FROM visible v WHERE (NOT ? OR unread) AND
              (?::uuid IS NULL OR (v.updated_at,v.room_id) <
                (SELECT updated_at,room_id FROM visible WHERE room_id=?))
            ORDER BY updated_at DESC,room_id DESC LIMIT 11
        """.trimIndent() + "\n"

        private val UNREAD_ROOMS_SQL = """
            SELECT count(*) FROM pawbridge_community.member_chat_room_members a
            JOIN pawbridge_community.member_chat_rooms r ON r.room_id=a.room_id
            WHERE a.member_id=? AND r.latest_sequence>a.hidden_through AND EXISTS (
              SELECT 1 FROM pawbridge_community.member_chat_messages m WHERE m.room_id=a.room_id
              AND m.sequence>a.read_through AND m.sender_id IS DISTINCT FROM ? AND m.expires_at>?)
        """.trimIndent() + "\n"

        private val UNREAD_ROOM_SQL = """
            SELECT EXISTS(SELECT 1 FROM pawbridge_community.member_chat_room_members a
              JOIN pawbridge_community.member_chat_messages m ON m.room_id=a.room_id
              WHERE a.member_id=? AND a.room_id=? AND m.sequence>a.read_through
              AND m.sender_id IS DISTINCT FROM ? AND m.expires_at>?)
        """.trimIndent() + "\n"

        private val READ_SQL = """
            UPDATE pawbridge_community.member_chat_room_members SET read_through=greatest(read_through,?)
            WHERE member_id=? AND room_id=?
        """.trimIndent() + "\n"

        private val HIDE_SQL = """
            UPDATE pawbridge_community.member_chat_room_members a SET hidden_through=r.latest_sequence
            FROM pawbridge_community.member_chat_rooms r WHERE a.room_id=r.room_id AND a.member_id=? AND a.room_id=?
        """.trimIndent() + "\n"

        private const val WITHDRAW_ROOMS_SQL =
            "SELECT room_id FROM pawbridge_community.member_chat_room_members WHERE member_id=?"
        private const val WITHDRAW_ROOM_MEMBERS_SQL =
            "DELETE FROM pawbridge_community.member_chat_room_members WHERE member_id=?"
        private const val WITHDRAW_REQUESTS_SQL =
            "DELETE FROM pawbridge_community.member_chat_requests WHERE sender_id=?"
        private const val WITHDRAW_MESSAGES_SQL =
            "UPDATE pawbridge_community.member_chat_messages SET sender_id=NULL WHERE sender_id=?"
        private const val WITHDRAW_FIRST_MEMBER_SQL =
            "UPDATE pawbridge_community.member_chat_rooms SET first_member_id=NULL WHERE first_member_id=?"
        private const val WITHDRAW_SECOND_MEMBER_SQL =
            "UPDATE pawbridge_community.member_chat_rooms SET second_member_id=NULL WHERE second_member_id=?"

        private val DELETE_EMPTY_ROOM_SQL = """
            DELETE FROM pawbridge_community.member_chat_rooms r WHERE room_id=? AND NOT EXISTS(
              SELECT 1 FROM pawbridge_community.member_chat_room_members a WHERE a.room_id=r.room_id)
        """.trimIndent() + "\n"

        private val EXPIRE_MESSAGES_SQL = """
            DELETE FROM pawbridge_community.member_chat_messages WHERE (room_id,sequence) IN
            (SELECT room_id,sequence FROM pawbridge_community.member_chat_messages
             WHERE expires_at<=? ORDER BY expires_at LIMIT 300 FOR UPDATE SKIP LOCKED)
        """.trimIndent() + "\n"

        private val EXPIRE_REQUESTS_SQL = """
            DELETE FROM pawbridge_community.member_chat_requests WHERE (sender_id,request_id) IN
            (SELECT sender_id,request_id FROM pawbridge_community.member_chat_requests
             WHERE created_at<? ORDER BY created_at LIMIT 300 FOR UPDATE SKIP LOCKED)
        """.trimIndent() + "\n"
    }
}
