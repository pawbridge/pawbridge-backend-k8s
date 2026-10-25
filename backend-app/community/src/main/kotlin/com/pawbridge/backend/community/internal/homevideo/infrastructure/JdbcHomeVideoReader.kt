package com.pawbridge.backend.community.internal.homevideo.infrastructure

import com.pawbridge.backend.community.internal.homevideo.application.HomeVideo
import com.pawbridge.backend.community.internal.homevideo.application.HomeVideoReader
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import org.springframework.jdbc.core.JdbcOperations
import org.springframework.jdbc.core.RowMapper
import org.springframework.transaction.support.TransactionOperations

class JdbcHomeVideoReader(
    private val jdbc: JdbcOperations,
    private val readOnlyTransaction: TransactionOperations,
) : HomeVideoReader {

    override fun read(cutoff: Instant): List<HomeVideo> =
        readOnlyTransaction.execute {
            jdbc.query(READ_VISIBLE_SQL, RowMapper { row, _ -> map(row) }, Timestamp.from(cutoff))
        }!!

    private companion object {
        private val READ_VISIBLE_SQL = """
            SELECT id, video_id, published, position, created_at, title, channel_title,
                   thumbnail_url, duration_seconds, available, checked_at
            FROM pawbridge_community.home_videos
            WHERE published AND available AND checked_at > ?
            ORDER BY position, created_at, id
            LIMIT 3
        """.trimIndent() + "\n"

        private fun map(row: ResultSet): HomeVideo {
            val checkedAt = row.getTimestamp("checked_at")
            return HomeVideo(
                row.getObject("id", UUID::class.java),
                row.getString("video_id"),
                row.getBoolean("published"),
                row.getInt("position"),
                row.getTimestamp("created_at").toInstant(),
                row.getString("title"),
                row.getString("channel_title"),
                row.getString("thumbnail_url"),
                row.getObject("duration_seconds") as Long?,
                row.getBoolean("available"),
                checkedAt?.toInstant(),
            )
        }
    }
}
