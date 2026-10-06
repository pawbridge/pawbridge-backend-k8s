package com.pawbridge.communityservice.contact;

import static com.pawbridge.communityservice.contact.PrivateNoteModels.*;
import java.sql.ResultSet;
import java.sql.SQLException;
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
public class PrivateNoteRepository {
    private final JdbcTemplate jdbc;
    public PrivateNoteRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    private static final String TABLES = " FROM pawbridge_community.private_note_mailboxes m JOIN pawbridge_community.private_notes n ON n.note_id=m.note_id ";

    /** One deterministic member lock order for send, block and withdrawal across app instances. */
    public void lockMembers(long first, long second) {
        for (long id : java.util.stream.LongStream.of(first, second).distinct().sorted().toArray()) {
            jdbc.update("INSERT INTO pawbridge_community.private_note_members(member_id) VALUES (?) ON CONFLICT DO NOTHING", id);
            jdbc.queryForObject("SELECT member_id FROM pawbridge_community.private_note_members WHERE member_id=? FOR UPDATE", Long.class, id);
        }
    }

    public record Request(UUID noteId, String hash) {}
    public Optional<Request> request(long sender, UUID key) {
        return jdbc.query("SELECT note_id,request_hash FROM pawbridge_community.private_note_requests WHERE sender_id=? AND request_id=?",
                (r,i) -> new Request(r.getObject(1, UUID.class), r.getString(2)), sender, key).stream().findFirst();
    }
    public long rate(long sender, Instant since) {
        return jdbc.queryForObject("SELECT count(*) FROM pawbridge_community.private_note_requests WHERE sender_id=? AND created_at>=?", Long.class, sender, Timestamp.from(since));
    }
    public boolean blocked(long a, long b) {
        return jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM pawbridge_community.private_note_blocks WHERE (member_id=? AND blocked_id=?) OR (member_id=? AND blocked_id=?))", Boolean.class, a,b,b,a);
    }
    public void insert(long sender, SendNote input, String body, UUID id, String hash, Instant now, Instant expiry) {
        jdbc.update("INSERT INTO pawbridge_community.private_notes(note_id,sender_id,recipient_id,body,reply_to,context_type,context_id,created_at,expires_at) VALUES (?,?,?,?,?,?,?,?,?)",
                id,sender,input.recipientId(),body,input.replyTo(),input.contextType(),input.contextId(),Timestamp.from(now),Timestamp.from(expiry));
        jdbc.update("INSERT INTO pawbridge_community.private_note_mailboxes(member_id,note_id,direction,read_at,created_at) VALUES (?,?,'SENT',?,?),(?,?,'INBOX',NULL,?)",
                sender,id,Timestamp.from(now),Timestamp.from(now),input.recipientId(),id,Timestamp.from(now));
        jdbc.update("INSERT INTO pawbridge_community.private_note_requests(sender_id,request_id,request_hash,note_id,created_at) VALUES (?,?,?,?,?)",
                sender,input.requestId(),hash,id,Timestamp.from(now));
    }
    public Optional<Note> find(long owner, UUID id, Instant now) {
        return jdbc.query("SELECT n.*,m.direction,m.read_at,m.favorite"+TABLES+" WHERE m.member_id=? AND n.note_id=? AND n.expires_at>?",
                PrivateNoteRepository::note,owner,id,Timestamp.from(now)).stream().findFirst();
    }
    public void lockNote(UUID id) {
        jdbc.query("SELECT note_id FROM pawbridge_community.private_notes WHERE note_id=? FOR UPDATE",(r,i)->r.getObject(1,UUID.class),id);
    }
    public List<Note> list(long owner, String box, boolean favorites, int page, Instant now) {
        return jdbc.query("SELECT n.*,m.direction,m.read_at,m.favorite"+TABLES+
                " WHERE m.member_id=? AND m.direction=? AND (NOT ? OR m.favorite) AND n.expires_at>? ORDER BY m.created_at DESC,m.note_id DESC LIMIT 10 OFFSET ?",
                PrivateNoteRepository::note,owner,box,favorites,Timestamp.from(now),(long)page*10);
    }
    public long count(long owner, String box, boolean favorites, Instant now) {
        return jdbc.queryForObject("SELECT count(*)"+TABLES+" WHERE m.member_id=? AND m.direction=? AND (NOT ? OR m.favorite) AND n.expires_at>?",Long.class,owner,box,favorites,Timestamp.from(now));
    }
    public long unread(long owner, Instant now) {
        return jdbc.queryForObject("SELECT count(*)"+TABLES+" WHERE m.member_id=? AND m.direction='INBOX' AND m.read_at IS NULL AND n.expires_at>?",Long.class,owner,Timestamp.from(now));
    }
    public List<Note> notifications(long owner, UUID cursor, Instant now) {
        // Cursor timestamp is looked up only in the authenticated member's retained inbox.
        if (cursor == null) return jdbc.query("SELECT n.*,m.direction,m.read_at,m.favorite"+TABLES+
                " WHERE m.member_id=? AND m.direction='INBOX' AND n.expires_at>? ORDER BY m.created_at DESC,m.note_id DESC LIMIT 21",PrivateNoteRepository::note,owner,Timestamp.from(now));
        return jdbc.query("SELECT n.*,m.direction,m.read_at,m.favorite"+TABLES+
                " WHERE m.member_id=? AND m.direction='INBOX' AND n.expires_at>? AND (m.created_at,m.note_id)<(SELECT created_at,note_id FROM pawbridge_community.private_note_mailboxes WHERE member_id=? AND note_id=? AND direction='INBOX') ORDER BY m.created_at DESC,m.note_id DESC LIMIT 21",
                PrivateNoteRepository::note,owner,Timestamp.from(now),owner,cursor);
    }
    public void read(long owner, UUID id, Instant now) {
        jdbc.update("UPDATE pawbridge_community.private_note_mailboxes SET read_at=COALESCE(read_at,?) WHERE member_id=? AND note_id=? AND direction='INBOX'",Timestamp.from(now),owner,id);
    }
    public void favorite(long owner, UUID id, boolean value) {
        jdbc.update("UPDATE pawbridge_community.private_note_mailboxes SET favorite=? WHERE member_id=? AND note_id=?",value,owner,id);
    }
    public void delete(long owner, UUID id) {
        jdbc.update("DELETE FROM pawbridge_community.private_note_mailboxes WHERE member_id=? AND note_id=?",owner,id);
        jdbc.update("DELETE FROM pawbridge_community.private_notes n WHERE note_id=? AND NOT EXISTS(SELECT 1 FROM pawbridge_community.private_note_mailboxes m WHERE m.note_id=n.note_id)",id);
    }
    public boolean contextAuthor(String type, long context, long recipient) {
        String table = "POST".equals(type) ? "posts" : "animal_reports";
        String id = "POST".equals(type) ? "post_id" : "report_id";
        return jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM pawbridge_community."+table+" WHERE "+id+"=? AND author_id=? AND deleted_at IS NULL)",Boolean.class,context,recipient);
    }
    public void block(long owner, long target, Instant now) {
        jdbc.update("INSERT INTO pawbridge_community.private_note_blocks(member_id,blocked_id,created_at) VALUES (?,?,?) ON CONFLICT DO NOTHING",owner,target,Timestamp.from(now));
    }
    public void unblock(long owner, long target) { jdbc.update("DELETE FROM pawbridge_community.private_note_blocks WHERE member_id=? AND blocked_id=?",owner,target); }
    public record Block(long id, Instant createdAt) {}
    public List<Block> blocks(long owner, int page) {
        return jdbc.query("SELECT blocked_id,created_at FROM pawbridge_community.private_note_blocks WHERE member_id=? ORDER BY created_at DESC,blocked_id DESC LIMIT 10 OFFSET ?",(r,i)->new Block(r.getLong(1),r.getTimestamp(2).toInstant()),owner,(long)page*10);
    }
    public long blockCount(long owner) { return jdbc.queryForObject("SELECT count(*) FROM pawbridge_community.private_note_blocks WHERE member_id=?",Long.class,owner); }
    public void withdraw(long member) {
        // Match mailbox deletion's note-before-request lock order; never scan/delete other members' orphans.
        List<UUID> affected=jdbc.query("SELECT note_id FROM pawbridge_community.private_notes WHERE sender_id=? OR recipient_id=? ORDER BY note_id FOR UPDATE",
                (r,i)->r.getObject(1,UUID.class),member,member);
        // Destroy payload fingerprints involving the withdrawn person as well as plain profile IDs.
        jdbc.update("UPDATE pawbridge_community.private_note_requests SET request_hash=NULL WHERE note_id IN (SELECT note_id FROM pawbridge_community.private_notes WHERE sender_id=? OR recipient_id=?)",member,member);
        jdbc.update("UPDATE pawbridge_community.private_notes SET context_type=NULL,context_id=NULL WHERE sender_id=? OR recipient_id=?",member,member);
        jdbc.update("DELETE FROM pawbridge_community.private_note_members WHERE member_id=?",member);
        for(int start=0;start<affected.size();start+=300) {
            List<UUID> batch=affected.subList(start,Math.min(start+300,affected.size()));
            String placeholders=String.join(",",java.util.Collections.nCopies(batch.size(),"?"));
            jdbc.update("DELETE FROM pawbridge_community.private_notes n WHERE note_id IN ("+placeholders+") AND NOT EXISTS(SELECT 1 FROM pawbridge_community.private_note_mailboxes m WHERE m.note_id=n.note_id)",batch.toArray());
        }
    }
    public int expire(Instant now) {
        int removed=jdbc.update("DELETE FROM pawbridge_community.private_notes WHERE note_id IN (SELECT note_id FROM pawbridge_community.private_notes WHERE expires_at<=? ORDER BY expires_at LIMIT 300 FOR UPDATE SKIP LOCKED)",Timestamp.from(now));
        jdbc.update("DELETE FROM pawbridge_community.private_note_requests WHERE (sender_id,request_id) IN (SELECT sender_id,request_id FROM pawbridge_community.private_note_requests WHERE note_id IS NULL AND created_at<? ORDER BY created_at LIMIT 300 FOR UPDATE SKIP LOCKED)",Timestamp.from(now.minusSeconds(172800)));
        return removed;
    }
    private static Note note(ResultSet r,int row) throws SQLException {
        return new Note(r.getObject("note_id",UUID.class),(Long)r.getObject("sender_id"),(Long)r.getObject("recipient_id"),r.getString("body"),r.getObject("reply_to",UUID.class),
                r.getString("context_type"),(Long)r.getObject("context_id"),r.getTimestamp("created_at").toInstant(),r.getTimestamp("expires_at").toInstant(),r.getString("direction"),
                r.getTimestamp("read_at")==null?null:r.getTimestamp("read_at").toInstant(),r.getBoolean("favorite"));
    }
}
