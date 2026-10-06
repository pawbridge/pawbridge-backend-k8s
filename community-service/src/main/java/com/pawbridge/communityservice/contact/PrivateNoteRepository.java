package com.pawbridge.communityservice.contact;

import com.pawbridge.communityservice.contact.PrivateNoteModels.Note;
import com.pawbridge.communityservice.contact.PrivateNoteModels.SendNote;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.LongStream;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@Profile("postgresql")
public class PrivateNoteRepository {
    private static final int CLEANUP_BATCH_SIZE = 300;
    private static final String MAILBOX_JOIN = """
             FROM pawbridge_community.private_note_mailboxes m
             JOIN pawbridge_community.private_notes n ON n.note_id = m.note_id
            """;

    private final JdbcTemplate jdbc;

    public PrivateNoteRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Uses the same member lock order for sending, blocking and withdrawal. */
    public void lockMembers(long firstMemberId, long secondMemberId) {
        for (long memberId : LongStream.of(firstMemberId, secondMemberId).distinct().sorted().toArray()) {
            jdbc.update("""
                    INSERT INTO pawbridge_community.private_note_members(member_id)
                    VALUES (?) ON CONFLICT DO NOTHING
                    """, memberId);
            jdbc.queryForObject("""
                    SELECT member_id FROM pawbridge_community.private_note_members
                    WHERE member_id = ? FOR UPDATE
                    """, Long.class, memberId);
        }
    }

    public record Request(UUID noteId, String hash) {}

    public Optional<Request> request(long senderId, UUID requestId) {
        return jdbc.query("""
                SELECT note_id, request_hash FROM pawbridge_community.private_note_requests
                WHERE sender_id = ? AND request_id = ?
                """, (row, rowNumber) -> new Request(row.getObject("note_id", UUID.class), row.getString("request_hash")),
                senderId, requestId).stream().findFirst();
    }

    public long rate(long senderId, Instant since) {
        return jdbc.queryForObject("""
                SELECT count(*) FROM pawbridge_community.private_note_requests
                WHERE sender_id = ? AND created_at >= ?
                """, Long.class, senderId, Timestamp.from(since));
    }

    public boolean blocked(long firstMemberId, long secondMemberId) {
        return jdbc.queryForObject("""
                SELECT EXISTS(
                    SELECT 1 FROM pawbridge_community.private_note_blocks
                    WHERE (member_id = ? AND blocked_id = ?) OR (member_id = ? AND blocked_id = ?)
                )
                """, Boolean.class, firstMemberId, secondMemberId, secondMemberId, firstMemberId);
    }

    public void insert(
            long senderId, SendNote request, String body, UUID noteId,
            String requestHash, Instant createdAt, Instant expiresAt) {
        jdbc.update("""
                INSERT INTO pawbridge_community.private_notes(
                    note_id, sender_id, recipient_id, body, reply_to,
                    context_type, context_id, created_at, expires_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, noteId, senderId, request.recipientId(), body, request.replyTo(), request.contextType(),
                request.contextId(), Timestamp.from(createdAt), Timestamp.from(expiresAt));
        jdbc.update("""
                INSERT INTO pawbridge_community.private_note_mailboxes(
                    member_id, note_id, direction, read_at, created_at
                ) VALUES (?, ?, 'SENT', ?, ?), (?, ?, 'INBOX', NULL, ?)
                """, senderId, noteId, Timestamp.from(createdAt), Timestamp.from(createdAt),
                request.recipientId(), noteId, Timestamp.from(createdAt));
        jdbc.update("""
                INSERT INTO pawbridge_community.private_note_requests(
                    sender_id, request_id, request_hash, note_id, created_at
                ) VALUES (?, ?, ?, ?, ?)
                """, senderId, request.requestId(), requestHash, noteId, Timestamp.from(createdAt));
    }

    public Optional<Note> find(long ownerId, UUID noteId, Instant now) {
        String sql = "SELECT n.*, m.direction, m.read_at, m.favorite" + MAILBOX_JOIN + """
                WHERE m.member_id = ? AND n.note_id = ? AND n.expires_at > ?
                """;
        return jdbc.query(sql, PrivateNoteRepository::mapNote, ownerId, noteId, Timestamp.from(now))
                .stream().findFirst();
    }

    public void lockNote(UUID noteId) {
        jdbc.query("""
                SELECT note_id FROM pawbridge_community.private_notes
                WHERE note_id = ? FOR UPDATE
                """, (row, rowNumber) -> row.getObject(1, UUID.class), noteId);
    }

    public List<Note> list(long ownerId, String box, boolean favoritesOnly, int page, Instant now) {
        String sql = "SELECT n.*, m.direction, m.read_at, m.favorite" + MAILBOX_JOIN + """
                WHERE m.member_id = ? AND m.direction = ? AND (NOT ? OR m.favorite) AND n.expires_at > ?
                ORDER BY m.created_at DESC, m.note_id DESC LIMIT 10 OFFSET ?
                """;
        return jdbc.query(sql, PrivateNoteRepository::mapNote,
                ownerId, box, favoritesOnly, Timestamp.from(now), (long) page * 10);
    }

    public long count(long ownerId, String box, boolean favoritesOnly, Instant now) {
        String sql = "SELECT count(*)" + MAILBOX_JOIN + """
                WHERE m.member_id = ? AND m.direction = ? AND (NOT ? OR m.favorite) AND n.expires_at > ?
                """;
        return jdbc.queryForObject(sql, Long.class, ownerId, box, favoritesOnly, Timestamp.from(now));
    }

    public long unread(long ownerId, Instant now) {
        String sql = "SELECT count(*)" + MAILBOX_JOIN + """
                WHERE m.member_id = ? AND m.direction = 'INBOX' AND m.read_at IS NULL AND n.expires_at > ?
                """;
        return jdbc.queryForObject(sql, Long.class, ownerId, Timestamp.from(now));
    }

    public List<Note> notifications(long ownerId, UUID cursor, Instant now) {
        if (cursor == null) {
            String sql = "SELECT n.*, m.direction, m.read_at, m.favorite" + MAILBOX_JOIN + """
                    WHERE m.member_id = ? AND m.direction = 'INBOX' AND n.expires_at > ?
                    ORDER BY m.created_at DESC, m.note_id DESC LIMIT 21
                    """;
            return jdbc.query(sql, PrivateNoteRepository::mapNote, ownerId, Timestamp.from(now));
        }
        // Resolve the cursor only within this member's inbox; never use another member's timestamp.
        String sql = "SELECT n.*, m.direction, m.read_at, m.favorite" + MAILBOX_JOIN + """
                WHERE m.member_id = ? AND m.direction = 'INBOX' AND n.expires_at > ?
                  AND (m.created_at, m.note_id) < (
                      SELECT created_at, note_id FROM pawbridge_community.private_note_mailboxes
                      WHERE member_id = ? AND note_id = ? AND direction = 'INBOX'
                  )
                ORDER BY m.created_at DESC, m.note_id DESC LIMIT 21
                """;
        return jdbc.query(sql, PrivateNoteRepository::mapNote, ownerId, Timestamp.from(now), ownerId, cursor);
    }

    public void read(long ownerId, UUID noteId, Instant readAt) {
        jdbc.update("""
                UPDATE pawbridge_community.private_note_mailboxes SET read_at = COALESCE(read_at, ?)
                WHERE member_id = ? AND note_id = ? AND direction = 'INBOX'
                """, Timestamp.from(readAt), ownerId, noteId);
    }

    public void favorite(long ownerId, UUID noteId, boolean favorite) {
        jdbc.update("""
                UPDATE pawbridge_community.private_note_mailboxes SET favorite = ?
                WHERE member_id = ? AND note_id = ?
                """, favorite, ownerId, noteId);
    }

    public void delete(long ownerId, UUID noteId) {
        jdbc.update("""
                DELETE FROM pawbridge_community.private_note_mailboxes WHERE member_id = ? AND note_id = ?
                """, ownerId, noteId);
        jdbc.update("""
                DELETE FROM pawbridge_community.private_notes n
                WHERE note_id = ? AND NOT EXISTS(
                    SELECT 1 FROM pawbridge_community.private_note_mailboxes m WHERE m.note_id = n.note_id
                )
                """, noteId);
    }

    public boolean contextAuthor(String contextType, long contextId, long recipientId) {
        // Identifiers come only from the service-validated POST/REPORT enum values.
        String table = "POST".equals(contextType) ? "posts" : "animal_reports";
        String idColumn = "POST".equals(contextType) ? "post_id" : "report_id";
        String sql = """
                SELECT EXISTS(
                    SELECT 1 FROM pawbridge_community.%s
                    WHERE %s = ? AND author_id = ? AND deleted_at IS NULL
                )
                """.formatted(table, idColumn);
        return jdbc.queryForObject(sql, Boolean.class, contextId, recipientId);
    }

    public void block(long ownerId, long blockedUserId, Instant createdAt) {
        jdbc.update("""
                INSERT INTO pawbridge_community.private_note_blocks(member_id, blocked_id, created_at)
                VALUES (?, ?, ?) ON CONFLICT DO NOTHING
                """, ownerId, blockedUserId, Timestamp.from(createdAt));
    }

    public void unblock(long ownerId, long blockedUserId) {
        jdbc.update("""
                DELETE FROM pawbridge_community.private_note_blocks WHERE member_id = ? AND blocked_id = ?
                """, ownerId, blockedUserId);
    }

    public record Block(long id, Instant createdAt) {}

    public List<Block> blocks(long ownerId, int page) {
        return jdbc.query("""
                SELECT blocked_id, created_at FROM pawbridge_community.private_note_blocks
                WHERE member_id = ? ORDER BY created_at DESC, blocked_id DESC LIMIT 10 OFFSET ?
                """, (row, rowNumber) -> new Block(row.getLong("blocked_id"), row.getTimestamp("created_at").toInstant()),
                ownerId, (long) page * 10);
    }

    public long blockCount(long ownerId) {
        return jdbc.queryForObject("""
                SELECT count(*) FROM pawbridge_community.private_note_blocks WHERE member_id = ?
                """, Long.class, ownerId);
    }

    public void withdraw(long userId) {
        // Lock originals before requests, matching mailbox deletion's lock order.
        List<UUID> affectedNotes = jdbc.query("""
                SELECT note_id FROM pawbridge_community.private_notes
                WHERE sender_id = ? OR recipient_id = ? ORDER BY note_id FOR UPDATE
                """, (row, rowNumber) -> row.getObject("note_id", UUID.class), userId, userId);
        jdbc.update("""
                UPDATE pawbridge_community.private_note_requests SET request_hash = NULL
                WHERE note_id IN (
                    SELECT note_id FROM pawbridge_community.private_notes WHERE sender_id = ? OR recipient_id = ?
                )
                """, userId, userId);
        jdbc.update("""
                UPDATE pawbridge_community.private_notes SET context_type = NULL, context_id = NULL
                WHERE sender_id = ? OR recipient_id = ?
                """, userId, userId);
        jdbc.update("DELETE FROM pawbridge_community.private_note_members WHERE member_id = ?", userId);
        deleteAffectedOrphans(affectedNotes);
    }

    private void deleteAffectedOrphans(List<UUID> affectedNotes) {
        for (int start = 0; start < affectedNotes.size(); start += CLEANUP_BATCH_SIZE) {
            List<UUID> batch = affectedNotes.subList(start, Math.min(start + CLEANUP_BATCH_SIZE, affectedNotes.size()));
            String placeholders = String.join(",", Collections.nCopies(batch.size(), "?"));
            String sql = """
                    DELETE FROM pawbridge_community.private_notes n
                    WHERE note_id IN (%s) AND NOT EXISTS(
                        SELECT 1 FROM pawbridge_community.private_note_mailboxes m WHERE m.note_id = n.note_id
                    )
                    """.formatted(placeholders);
            jdbc.update(sql, batch.toArray());
        }
    }

    public int expire(Instant now) {
        int removed = jdbc.update("""
                DELETE FROM pawbridge_community.private_notes WHERE note_id IN (
                    SELECT note_id FROM pawbridge_community.private_notes
                    WHERE expires_at <= ? ORDER BY expires_at LIMIT 300 FOR UPDATE SKIP LOCKED
                )
                """, Timestamp.from(now));
        jdbc.update("""
                DELETE FROM pawbridge_community.private_note_requests WHERE (sender_id, request_id) IN (
                    SELECT sender_id, request_id FROM pawbridge_community.private_note_requests
                    WHERE note_id IS NULL AND created_at < ?
                    ORDER BY created_at LIMIT 300 FOR UPDATE SKIP LOCKED
                )
                """, Timestamp.from(now.minusSeconds(172800)));
        return removed;
    }

    private static Note mapNote(ResultSet row, int rowNumber) throws SQLException {
        Timestamp readAt = row.getTimestamp("read_at");
        return new Note(
                row.getObject("note_id", UUID.class),
                (Long) row.getObject("sender_id"),
                (Long) row.getObject("recipient_id"),
                row.getString("body"),
                row.getObject("reply_to", UUID.class),
                row.getString("context_type"),
                (Long) row.getObject("context_id"),
                row.getTimestamp("created_at").toInstant(),
                row.getTimestamp("expires_at").toInstant(),
                row.getString("direction"),
                readAt == null ? null : readAt.toInstant(),
                row.getBoolean("favorite"));
    }
}
