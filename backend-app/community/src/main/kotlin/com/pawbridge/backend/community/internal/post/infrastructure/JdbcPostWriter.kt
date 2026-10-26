package com.pawbridge.backend.community.internal.post.infrastructure

import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import com.pawbridge.backend.community.internal.post.application.PostModification
import com.pawbridge.backend.community.internal.post.application.PostNotFoundException
import com.pawbridge.backend.community.internal.post.application.PostSnapshot
import com.pawbridge.backend.community.internal.post.application.PostWriter
import com.pawbridge.backend.community.internal.post.application.UnsupportedPostCharactersException
import com.pawbridge.backend.community.internal.post.domain.PostOwnership
import com.pawbridge.backend.community.internal.post.domain.PostToCreate
import com.pawbridge.backend.community.internal.post.infrastructure.search.KoreanSearchTerms
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.SQLException
import java.sql.Timestamp
import java.time.Clock
import java.time.LocalDateTime
import java.util.Calendar
import java.util.TimeZone
import org.springframework.jdbc.core.JdbcOperations
import org.springframework.jdbc.core.PreparedStatementCreator
import org.springframework.jdbc.core.ResultSetExtractor
import org.springframework.transaction.support.TransactionOperations

class JdbcPostWriter(
    private val jdbc: JdbcOperations,
    private val transactions: TransactionOperations,
    private val objectMapper: ObjectMapper,
    private val clock: Clock,
) : PostWriter, PostModification, AutoCloseable {

    private val terms = KoreanSearchTerms()

    constructor(jdbc: JdbcOperations, transactions: TransactionOperations, objectMapper: ObjectMapper) :
        this(jdbc, transactions, objectMapper, Clock.systemDefaultZone())

    override fun create(post: PostToCreate): PostSnapshot {
        try {
            return transactions.execute { write(post) }!!
        } catch (failure: RuntimeException) {
            // Includes structured constraint failures reported while committing.
            if (PostgresqlRollbackCharsetViolation.matches(failure)) {
                throw UnsupportedPostCharactersException(failure)
            }
            throw failure
        }
    }

    private fun write(post: PostToCreate): PostSnapshot {
        val now = LocalDateTime.now(clock)
        val postId: Long? = jdbc.query(
            PreparedStatementCreator { connection ->
                prepare(
                    connection,
                    INSERT_POST,
                    post.authorId(),
                    post.title(),
                    post.content(),
                    post.boardType().name,
                    json(post.imageUrls()),
                    now,
                    now,
                )
            },
            ResultSetExtractor { result ->
                if (!result.next()) {
                    throw IllegalStateException("Post insert returned no identity")
                }
                result.getLong("post_id")
            },
        )
        if (postId == null) {
            throw IllegalStateException("Post insert returned no identity")
        }
        writeSearchDocument(postId, post.title(), post.content())
        return PostSnapshot(
            postId,
            post.authorId(),
            post.title(),
            post.content(),
            post.boardType().name,
            post.imageUrls(),
            now,
            now,
        )
    }

    override fun update(
        postId: Long,
        authorId: Long,
        title: String?,
        content: String?,
        imageUrls: List<String?>?,
    ): PostSnapshot {
        try {
            return transactions.execute { updateInTransaction(postId, authorId, title, content, imageUrls) }!!
        } catch (failure: RuntimeException) {
            if (PostgresqlRollbackCharsetViolation.matches(failure)) {
                throw UnsupportedPostCharactersException(failure)
            }
            throw failure
        }
    }

    private fun updateInTransaction(
        postId: Long,
        authorId: Long,
        title: String?,
        content: String?,
        imageUrls: List<String?>?,
    ): PostSnapshot {
        val current: PostSnapshot? = jdbc.query(
            PreparedStatementCreator { connection -> prepare(connection, LOCK_POST, postId) },
            ResultSetExtractor { row ->
                if (!row.next()) {
                    throw PostNotFoundException()
                }
                val utc = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
                PostSnapshot(
                    row.getLong("post_id"),
                    row.getLong("author_id"),
                    row.getString("title"),
                    row.getString("content"),
                    row.getString("board_type"),
                    images(row.getString("image_urls")),
                    row.getTimestamp("created_at", utc).toLocalDateTime(),
                    row.getTimestamp("updated_at", utc).toLocalDateTime(),
                )
            },
        )
        if (current == null) {
            throw PostNotFoundException()
        }
        PostOwnership.requireAuthor(current.authorId(), authorId)
        val nextTitle = title ?: current.title()
        val nextContent = content ?: current.content()
        val nextImages = imageUrls ?: current.imageUrls()
        val changed = current.title() != nextTitle ||
            current.content() != nextContent ||
            current.imageUrls() != nextImages
        if (changed) {
            val updated = jdbc.update(
                PreparedStatementCreator { connection ->
                    prepare(
                        connection,
                        UPDATE_POST,
                        nextTitle,
                        nextContent,
                        json(nextImages),
                        LocalDateTime.now(clock),
                        postId,
                        authorId,
                    )
                },
            )
            if (updated != 1) {
                throw PostNotFoundException()
            }
        }
        // Even an unchanged PUT refreshes its search projection.
        writeSearchDocument(postId, nextTitle, nextContent)
        // The legacy immutable response is built before JPA dirty flush updates updatedAt at commit.
        return PostSnapshot(
            postId,
            authorId,
            nextTitle,
            nextContent,
            current.boardType(),
            nextImages,
            current.createdAt(),
            current.updatedAt(),
        )
    }

    private fun images(value: String?): List<String>? {
        if (value == null || value.isEmpty()) {
            return emptyList()
        }
        try {
            return objectMapper.readValue(value, IMAGE_URLS_TYPE)
        } catch (failure: JsonProcessingException) {
            throw IllegalStateException("Failed to read post image URLs", failure)
        }
    }

    // Search recovery shares this writer's exact upsert and analyzer; it owns no second analyzer lifecycle.
    // Package-private in Java; internal here because Kotlin has no package-private visibility.
    internal fun writeSearchDocument(postId: Long, title: String?, content: String?) {
        jdbc.update(
            PreparedStatementCreator { connection ->
                prepare(
                    connection,
                    INSERT_SEARCH_DOCUMENT,
                    postId,
                    title,
                    content,
                    KoreanSearchTerms.VERSION,
                    terms.document(title),
                    terms.document(content),
                )
            },
        )
    }

    private fun prepare(connection: Connection, sql: String, vararg values: Any?): PreparedStatement {
        val statement = connection.prepareStatement(sql)
        try {
            for (index in values.indices) {
                val value = values[index]
                if (value is LocalDateTime) {
                    // Hibernate's TIMESTAMP binding uses Timestamp.valueOf plus its UTC JDBC calendar.
                    statement.setTimestamp(
                        index + 1,
                        Timestamp.valueOf(value),
                        Calendar.getInstance(TimeZone.getTimeZone("UTC")),
                    )
                } else {
                    statement.setObject(index + 1, value)
                }
            }
            return statement
        } catch (failure: Exception) {
            try {
                statement.close()
            } catch (closeFailure: SQLException) {
                failure.addSuppressed(closeFailure)
            }
            throw failure
        }
    }

    private fun json(value: Any?): String {
        try {
            return objectMapper.writeValueAsString(value)
        } catch (failure: JsonProcessingException) {
            throw IllegalStateException("Failed to serialize post data", failure)
        }
    }

    override fun close() {
        terms.close()
    }

    private companion object {
        private val INSERT_POST = """
            INSERT INTO pawbridge_community.posts
                (author_id, title, content, board_type, image_urls, created_at, updated_at)
            VALUES (?, ?, ?, ?, CAST(? AS json), ?, ?)
            RETURNING post_id
        """.trimIndent() + "\n"

        private val INSERT_SEARCH_DOCUMENT = """
            INSERT INTO pawbridge_community.post_search_documents
                (post_id, title_hash, content_hash, analyzer_version, tokens)
            VALUES (?, md5(?), md5(?), ?,
                setweight(to_tsvector('simple', ?), 'A') || setweight(to_tsvector('simple', ?), 'B'))
            ON CONFLICT (post_id) DO UPDATE SET title_hash = excluded.title_hash,
                content_hash = excluded.content_hash, analyzer_version = excluded.analyzer_version,
                tokens = excluded.tokens
        """.trimIndent() + "\n"

        private val LOCK_POST = """
            SELECT post_id, author_id, title, content, board_type, image_urls, created_at, updated_at
            FROM pawbridge_community.posts WHERE post_id = ? AND deleted_at IS NULL FOR UPDATE
        """.trimIndent() + "\n"

        private val UPDATE_POST = """
            UPDATE pawbridge_community.posts SET title = ?, content = ?, image_urls = CAST(? AS json), updated_at = ?
            WHERE post_id = ? AND author_id = ? AND deleted_at IS NULL
        """.trimIndent() + "\n"

        private val IMAGE_URLS_TYPE = object : TypeReference<List<String>>() {}
    }
}
