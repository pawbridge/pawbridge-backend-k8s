package com.pawbridge.backend.community.internal.post.infrastructure

import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import com.pawbridge.backend.community.internal.post.application.PostSearchReader
import com.pawbridge.backend.community.internal.post.application.PostSearchUnavailableException
import com.pawbridge.backend.community.internal.post.application.PostSnapshot
import com.pawbridge.backend.community.internal.post.domain.PostBoardType
import com.pawbridge.backend.community.internal.post.infrastructure.search.KoreanSearchTerms
import java.sql.ResultSet
import java.time.LocalDateTime
import org.springframework.dao.DataAccessException
import org.springframework.jdbc.core.JdbcOperations
import org.springframework.jdbc.core.RowMapper
import org.springframework.transaction.CannotCreateTransactionException
import org.springframework.transaction.TransactionTimedOutException
import org.springframework.transaction.support.TransactionOperations

class JdbcPostSearchReader(
    private val jdbc: JdbcOperations,
    private val transactions: TransactionOperations,
    private val objectMapper: ObjectMapper,
) : PostSearchReader, AutoCloseable {

    private val terms = KoreanSearchTerms()

    override fun search(keyword: String?): List<PostSnapshot> {
        val query = terms.query(keyword)
        if (query.isEmpty()) {
            return emptyList()
        }
        try {
            return transactions.execute {
                jdbc.query(
                    SEARCH_POSTS,
                    RowMapper { row, _ -> mapPost(row) },
                    KoreanSearchTerms.VERSION,
                    query,
                    query,
                ).filterNotNull()
            }!!
        } catch (failure: DataAccessException) {
            throw PostSearchUnavailableException(failure)
        } catch (failure: CannotCreateTransactionException) {
            throw PostSearchUnavailableException(failure)
        } catch (failure: TransactionTimedOutException) {
            throw PostSearchUnavailableException(failure)
        }
    }

    private fun mapPost(row: ResultSet): PostSnapshot? {
        if (!row.getBoolean("ready")) {
            throw PostSearchUnavailableException()
        }
        if (row.getObject("post_id") == null) {
            return null
        }
        return PostSnapshot(
            row.getLong("post_id"),
            row.getLong("author_id"),
            row.getString("title"),
            row.getString("content"),
            PostBoardType.valueOf(row.getString("board_type")).name,
            readImageUrls(row.getString("image_urls")),
            row.getObject("created_at", LocalDateTime::class.java),
            row.getObject("updated_at", LocalDateTime::class.java),
        )
    }

    private fun readImageUrls(value: String?): List<String>? {
        if (value == null) {
            return emptyList()
        }
        try {
            return objectMapper.readValue(value, IMAGE_URLS_TYPE)
        } catch (failure: JsonProcessingException) {
            throw IllegalStateException("Invalid stored post image list", failure)
        }
    }

    override fun close() {
        terms.close()
    }

    private companion object {
        private val SEARCH_POSTS = """
            WITH readiness AS (
                SELECT NOT EXISTS(
                    SELECT 1 FROM pawbridge_community.posts p
                    LEFT JOIN pawbridge_community.post_search_documents d ON d.post_id = p.post_id
                    WHERE p.deleted_at IS NULL AND (d.post_id IS NULL OR d.title_hash <> md5(p.title)
                        OR d.content_hash <> md5(p.content) OR d.analyzer_version <> ?)) AS ready
            ), ranked AS (
                SELECT p.post_id, p.author_id, p.title, p.content, p.board_type, p.image_urls,
                    p.created_at, p.updated_at, ts_rank(d.tokens, to_tsquery('simple', ?)) AS score
                FROM pawbridge_community.posts p
                JOIN pawbridge_community.post_search_documents d ON d.post_id = p.post_id
                WHERE p.deleted_at IS NULL AND d.tokens @@ to_tsquery('simple', ?)
                ORDER BY score DESC, p.created_at DESC, p.post_id DESC LIMIT 10
            )
            SELECT readiness.ready, ranked.* FROM readiness LEFT JOIN ranked ON readiness.ready
            ORDER BY score DESC, created_at DESC, post_id DESC
        """.trimIndent() + "\n"

        private val IMAGE_URLS_TYPE = object : TypeReference<List<String>>() {}
    }
}
