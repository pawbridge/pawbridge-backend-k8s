package com.pawbridge.communityservice.chat;

import static com.pawbridge.communityservice.chat.MemberChatModels.*;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@Profile("postgresql")
public class MemberChatRepository {
    private final JdbcTemplate jdbc;

    public MemberChatRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record SavedRequest(String hash, Receipt receipt, boolean retained) {}
    public record Membership(UUID roomId, Long counterpartId, long latestSequence,
                             long readThrough, long counterpartReadThrough, long hiddenThrough) {}
    public record Summary(Membership membership, Instant updatedAt, String preview, boolean unread) {}

    public boolean installed() {
        return jdbc.queryForObject("SELECT to_regclass('pawbridge_community.member_chat_rooms') IS NOT NULL", Boolean.class);
    }

    public Optional<SavedRequest> request(long senderId, UUID requestId, Instant now) {
        return jdbc.query("""
                SELECT q.*, EXISTS(SELECT 1 FROM pawbridge_community.member_chat_messages m
                  WHERE m.room_id=q.room_id AND m.sequence=q.sequence AND m.expires_at>?) AS retained
                FROM pawbridge_community.member_chat_requests q WHERE sender_id=? AND request_id=?
                """, (row, n) -> new SavedRequest(row.getString("request_hash"),
                new Receipt(requestId, row.getObject("room_id", UUID.class), row.getLong("sequence")),
                row.getBoolean("retained")), Timestamp.from(now), senderId, requestId).stream().findFirst();
    }

    public long rate(long senderId, Instant since) {
        return jdbc.queryForObject("""
                SELECT count(*) FROM pawbridge_community.member_chat_requests WHERE sender_id=? AND created_at>=?
                """, Long.class, senderId, Timestamp.from(since));
    }

    public UUID roomForSend(long sender, long recipient) {
        long first = Math.min(sender, recipient);
        long second = Math.max(sender, recipient);
        UUID proposed = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO pawbridge_community.member_chat_rooms(room_id,first_member_id,second_member_id)
                VALUES (?,?,?) ON CONFLICT (first_member_id,second_member_id) DO NOTHING
                """, proposed, first, second);
        UUID room = jdbc.queryForObject("""
                SELECT room_id FROM pawbridge_community.member_chat_rooms
                WHERE first_member_id=? AND second_member_id=? FOR UPDATE
                """, UUID.class, first, second);
        jdbc.update("""
                INSERT INTO pawbridge_community.member_chat_room_members(room_id,member_id)
                VALUES (?,?),(?,?) ON CONFLICT DO NOTHING
                """, room, first, room, second);
        return room;
    }

    public Optional<Membership> membership(long memberId, UUID roomId) {
        return jdbc.query("""
                SELECT r.*, a.read_through, a.hidden_through, b.member_id AS counterpart_id,
                       coalesce(b.read_through,0) AS counterpart_read_through
                FROM pawbridge_community.member_chat_rooms r
                JOIN pawbridge_community.member_chat_room_members a ON a.room_id=r.room_id AND a.member_id=?
                LEFT JOIN pawbridge_community.member_chat_room_members b ON b.room_id=r.room_id AND b.member_id<>a.member_id
                WHERE r.room_id=?
                """, (row, n) -> new Membership(roomId, row.getObject("counterpart_id", Long.class),
                row.getLong("latest_sequence"), row.getLong("read_through"),
                row.getLong("counterpart_read_through"), row.getLong("hidden_through")),
                memberId, roomId).stream().findFirst();
    }

    public Receipt insert(long sender, Send input, UUID roomId, String hash, Instant now, Instant expires) {
        long sequence = jdbc.queryForObject("""
                UPDATE pawbridge_community.member_chat_rooms SET latest_sequence=latest_sequence+1
                WHERE room_id=? RETURNING latest_sequence
                """, Long.class, roomId);
        jdbc.update("""
                INSERT INTO pawbridge_community.member_chat_messages
                (room_id,sequence,sender_id,body,context_type,context_id,created_at,expires_at)
                VALUES (?,?,?,?,?,?,?,?)
                """, roomId, sequence, sender, input.body(), input.contextType(), input.contextId(),
                Timestamp.from(now), Timestamp.from(expires));
        jdbc.update("""
                INSERT INTO pawbridge_community.member_chat_requests
                (sender_id,request_id,request_hash,room_id,sequence,created_at) VALUES (?,?,?,?,?,?)
                """, sender, input.requestId(), hash, roomId, sequence, Timestamp.from(now));
        return new Receipt(input.requestId(), roomId, sequence);
    }

    public List<Message> messages(UUID room, Long before, Long after, Instant now) {
        return jdbc.query("""
                SELECT * FROM pawbridge_community.member_chat_messages
                WHERE room_id=? AND expires_at>? AND (?::bigint IS NULL OR sequence<?)
                AND (?::bigint IS NULL OR sequence>?)
                ORDER BY CASE WHEN ?::bigint IS NOT NULL THEN sequence END ASC, sequence DESC LIMIT 51
                """, (row, n) -> new Message(room, row.getLong("sequence"),
                row.getObject("sender_id", Long.class), row.getString("body"),
                row.getString("context_type") == null ? null
                    : ("POST".equals(row.getString("context_type")) ? "/community/" : "/reports/")
                        + row.getLong("context_id"),
                row.getTimestamp("created_at").toInstant()), room, Timestamp.from(now), before, before, after, after, after);
    }

    public Optional<Message> latest(UUID room, Instant now) {
        return jdbc.query("""
                SELECT room_id,sequence,sender_id,body,created_at
                FROM pawbridge_community.member_chat_messages
                WHERE room_id=? AND expires_at>? ORDER BY sequence DESC LIMIT 1
                """, (row, n) -> new Message(room, row.getLong("sequence"),
                row.getObject("sender_id", Long.class), row.getString("body"), null,
                row.getTimestamp("created_at").toInstant()), room, Timestamp.from(now)).stream().findFirst();
    }

    public List<Summary> rooms(long owner, UUID cursor, boolean unreadOnly, Instant now) {
        return jdbc.query("""
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
                """, (row, n) -> new Summary(new Membership(row.getObject("room_id", UUID.class),
                row.getObject("counterpart_id", Long.class), row.getLong("latest_sequence"),
                row.getLong("read_through"), row.getLong("counterpart_read_through"), row.getLong("hidden_through")),
                row.getTimestamp("updated_at").toInstant(), row.getString("preview"), row.getBoolean("unread")),
                owner, Timestamp.from(now), Timestamp.from(now), owner, unreadOnly, cursor, cursor);
    }

    public long unreadRooms(long owner, Instant now) {
        return jdbc.queryForObject("""
                SELECT count(*) FROM pawbridge_community.member_chat_room_members a
                JOIN pawbridge_community.member_chat_rooms r ON r.room_id=a.room_id
                WHERE a.member_id=? AND r.latest_sequence>a.hidden_through AND EXISTS (
                  SELECT 1 FROM pawbridge_community.member_chat_messages m WHERE m.room_id=a.room_id
                  AND m.sequence>a.read_through AND m.sender_id IS DISTINCT FROM ? AND m.expires_at>?)
                """, Long.class, owner, owner, Timestamp.from(now));
    }

    public boolean unreadRoom(long owner, UUID room, Instant now) {
        return jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM pawbridge_community.member_chat_room_members a
                  JOIN pawbridge_community.member_chat_messages m ON m.room_id=a.room_id
                  WHERE a.member_id=? AND a.room_id=? AND m.sequence>a.read_through
                  AND m.sender_id IS DISTINCT FROM ? AND m.expires_at>?)
                """, Boolean.class, owner, room, owner, Timestamp.from(now));
    }

    public void read(long owner, UUID room, long through) {
        jdbc.update("""
                UPDATE pawbridge_community.member_chat_room_members SET read_through=greatest(read_through,?)
                WHERE member_id=? AND room_id=?
                """, through, owner, room);
    }

    public void hide(long owner, UUID room) {
        jdbc.update("""
                UPDATE pawbridge_community.member_chat_room_members a SET hidden_through=r.latest_sequence
                FROM pawbridge_community.member_chat_rooms r WHERE a.room_id=r.room_id AND a.member_id=? AND a.room_id=?
                """, owner, room);
    }

    public void withdraw(long memberId) {
        List<UUID> rooms = jdbc.queryForList("SELECT room_id FROM pawbridge_community.member_chat_room_members WHERE member_id=?", UUID.class, memberId);
        jdbc.update("DELETE FROM pawbridge_community.member_chat_room_members WHERE member_id=?", memberId);
        jdbc.update("DELETE FROM pawbridge_community.member_chat_requests WHERE sender_id=?", memberId);
        jdbc.update("UPDATE pawbridge_community.member_chat_messages SET sender_id=NULL WHERE sender_id=?", memberId);
        jdbc.update("UPDATE pawbridge_community.member_chat_rooms SET first_member_id=NULL WHERE first_member_id=?", memberId);
        jdbc.update("UPDATE pawbridge_community.member_chat_rooms SET second_member_id=NULL WHERE second_member_id=?", memberId);
        for (UUID room : rooms) {
            jdbc.update("""
                    DELETE FROM pawbridge_community.member_chat_rooms r WHERE room_id=? AND NOT EXISTS(
                      SELECT 1 FROM pawbridge_community.member_chat_room_members a WHERE a.room_id=r.room_id)
                    """, room);
        }
    }

    public void expire(Instant now) {
        jdbc.update("""
                DELETE FROM pawbridge_community.member_chat_messages WHERE (room_id,sequence) IN
                (SELECT room_id,sequence FROM pawbridge_community.member_chat_messages
                 WHERE expires_at<=? ORDER BY expires_at LIMIT 300 FOR UPDATE SKIP LOCKED)
                """, Timestamp.from(now));
        jdbc.update("""
                DELETE FROM pawbridge_community.member_chat_requests WHERE (sender_id,request_id) IN
                (SELECT sender_id,request_id FROM pawbridge_community.member_chat_requests
                 WHERE created_at<? ORDER BY created_at LIMIT 300 FOR UPDATE SKIP LOCKED)
                """, Timestamp.from(now.atZone(java.time.ZoneOffset.UTC).minusYears(1).toInstant()));
    }
}
