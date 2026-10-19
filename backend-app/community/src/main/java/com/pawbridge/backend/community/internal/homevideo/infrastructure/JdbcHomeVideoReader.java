package com.pawbridge.backend.community.internal.homevideo.infrastructure;

import com.pawbridge.backend.community.internal.homevideo.application.HomeVideo;
import com.pawbridge.backend.community.internal.homevideo.application.HomeVideoReader;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.transaction.support.TransactionOperations;

public final class JdbcHomeVideoReader implements HomeVideoReader {
    private static final String READ_VISIBLE_SQL = """
            SELECT id, video_id, published, position, created_at, title, channel_title,
                   thumbnail_url, duration_seconds, available, checked_at
            FROM pawbridge_community.home_videos
            WHERE published AND available AND checked_at > ?
            ORDER BY position, created_at, id
            LIMIT 3
            """;

    private final JdbcOperations jdbc;
    private final TransactionOperations readOnlyTransaction;

    public JdbcHomeVideoReader(JdbcOperations jdbc, TransactionOperations readOnlyTransaction) {
        this.jdbc = jdbc;
        this.readOnlyTransaction = readOnlyTransaction;
    }

    @Override
    public List<HomeVideo> read(Instant cutoff) {
        return readOnlyTransaction.execute(status ->
                jdbc.query(READ_VISIBLE_SQL, JdbcHomeVideoReader::map, Timestamp.from(cutoff)));
    }

    private static HomeVideo map(ResultSet row, int number) throws SQLException {
        Timestamp checkedAt = row.getTimestamp("checked_at");
        return new HomeVideo(
                row.getObject("id", UUID.class),
                row.getString("video_id"),
                row.getBoolean("published"),
                row.getInt("position"),
                row.getTimestamp("created_at").toInstant(),
                row.getString("title"),
                row.getString("channel_title"),
                row.getString("thumbnail_url"),
                (Long) row.getObject("duration_seconds"),
                row.getBoolean("available"),
                checkedAt == null ? null : checkedAt.toInstant());
    }
}
