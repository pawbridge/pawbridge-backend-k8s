package com.pawbridge.backend.community.internal.homevideo.infrastructure;

import static com.pawbridge.backend.community.internal.homevideo.application.HomeVideoManagement.*;
import com.pawbridge.backend.community.internal.homevideo.application.HomeVideo;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;

public class JdbcHomeVideoRepository {
    private final JdbcTemplate jdbc;
    public JdbcHomeVideoRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public long lockBoard() {
        return jdbc.queryForObject("SELECT revision FROM pawbridge_community.home_video_board WHERE id=1 FOR UPDATE", Long.class);
    }
    public long revision() {
        return jdbc.queryForObject("SELECT revision FROM pawbridge_community.home_video_board WHERE id=1", Long.class);
    }
    public void changed() { jdbc.update("UPDATE pawbridge_community.home_video_board SET revision=revision+1 WHERE id=1"); }
    public List<HomeVideo> list() {
        return jdbc.query("SELECT * FROM pawbridge_community.home_videos ORDER BY position, created_at, id", JdbcHomeVideoRepository::map);
    }
    public void insert(UUID id, String videoId, boolean published, int position, Instant now) {
        jdbc.update("""
                INSERT INTO pawbridge_community.home_videos(id,video_id,published,position,created_at)
                VALUES (?,?,?,?,?)
                """, id, videoId, published, position, Timestamp.from(now));
    }
    public void selection(UUID id, String videoId, boolean published) {
        jdbc.update("UPDATE pawbridge_community.home_videos SET video_id=?,published=? WHERE id=?", videoId, published, id);
    }
    public void publication(UUID id, boolean published) {
        jdbc.update("UPDATE pawbridge_community.home_videos SET published=? WHERE id=?", published, id);
    }
    public void position(UUID id, int position) {
        jdbc.update("UPDATE pawbridge_community.home_videos SET position=? WHERE id=?", position, id);
    }
    public void metadata(UUID id, Metadata metadata, Instant checkedAt) {
        if (metadata == null || !metadata.available()) {
            jdbc.update("""
                    UPDATE pawbridge_community.home_videos SET title=NULL,channel_title=NULL,thumbnail_url=NULL,
                    duration_seconds=NULL,available=false,checked_at=? WHERE id=?
                    """, Timestamp.from(checkedAt), id);
        } else {
            jdbc.update("""
                    UPDATE pawbridge_community.home_videos SET title=?,channel_title=?,thumbnail_url=?,
                    duration_seconds=?,available=true,checked_at=? WHERE id=?
                    """, metadata.title(), metadata.channelTitle(), metadata.thumbnailUrl(), metadata.durationSeconds(),
                    Timestamp.from(checkedAt), id);
        }
    }
    public int expire(Instant cutoff) {
        // Clear provider metadata before its 30-day deadline, but retain user-selected ID/settings.
        return jdbc.update("""
                UPDATE pawbridge_community.home_videos SET title=NULL,channel_title=NULL,thumbnail_url=NULL,
                duration_seconds=NULL,available=false,checked_at=NULL WHERE checked_at <= ?
                """, Timestamp.from(cutoff));
    }
    private static HomeVideo map(ResultSet row, int number) throws SQLException {
        Timestamp checkedAt = row.getTimestamp("checked_at");
        return new HomeVideo(row.getObject("id", UUID.class), row.getString("video_id"), row.getBoolean("published"),
                row.getInt("position"), row.getTimestamp("created_at").toInstant(), row.getString("title"),
                row.getString("channel_title"), row.getString("thumbnail_url"), (Long)row.getObject("duration_seconds"),
                row.getBoolean("available"), checkedAt == null ? null : checkedAt.toInstant());
    }
}
