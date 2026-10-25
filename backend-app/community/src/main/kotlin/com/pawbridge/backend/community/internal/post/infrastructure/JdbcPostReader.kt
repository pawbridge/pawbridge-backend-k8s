package com.pawbridge.backend.community.internal.post.infrastructure

import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import com.pawbridge.backend.community.internal.post.application.PostListReader
import com.pawbridge.backend.community.internal.post.application.PostPageReader
import com.pawbridge.backend.community.internal.post.application.PostReader
import com.pawbridge.backend.community.internal.post.application.PostSnapshot
import java.sql.ResultSet
import java.util.Calendar
import java.util.Optional
import java.util.TimeZone
import org.springframework.dao.InvalidDataAccessApiUsageException
import org.springframework.jdbc.core.JdbcOperations
import org.springframework.jdbc.core.RowMapper
import org.springframework.transaction.support.TransactionOperations

class JdbcPostReader(
    private val jdbc: JdbcOperations,
    private val transactions: TransactionOperations,
    private val objectMapper: ObjectMapper,
) : PostReader, PostListReader, PostPageReader {

    override fun findById(postId: Long): Optional<PostSnapshot> =
        transactions.execute {
            jdbc.query(SELECT_POST, RowMapper { row, _ -> mapPost(row) }, postId)
                .stream()
                .findFirst()
        }!!

    override fun findAll(): List<PostSnapshot> =
        transactions.execute {
            jdbc.query(SELECT_POSTS, RowMapper { row, _ -> mapPost(row) })
        }!!

    override fun findPage(request: PostPageReader.Request): PostPageReader.Slice {
        val order = request.orders().joinToString(", ") { sortOrder -> sortExpression(sortOrder) }
        val sql = "SELECT post_id, author_id, title, content, board_type, image_urls, created_at, updated_at " +
            "FROM pawbridge_community.posts WHERE deleted_at IS NULL" +
            (if (order.isEmpty()) "" else " ORDER BY $order") + " LIMIT ? OFFSET ?"
        return transactions.execute {
            // The original JPA PageableUtils rejects offsets outside setFirstResult's integer range.
            if (request.offset() > Int.MAX_VALUE) {
                throw InvalidDataAccessApiUsageException("Page offset exceeds Integer.MAX_VALUE (2147483647)")
            }
            val posts = jdbc.query(
                sql,
                RowMapper { row, _ -> mapPost(row) },
                request.size(),
                request.offset(),
            )
            // Match Spring Data PageableExecutionUtils: a nonempty partial page supplies its own total.
            val total = if (posts.size < request.size() && (request.number() == 0 || !posts.isEmpty())) {
                request.offset() + posts.size
            } else {
                jdbc.queryForObject(
                    "SELECT count(*) FROM pawbridge_community.posts WHERE deleted_at IS NULL",
                    Long::class.javaObjectType,
                )!!
            }
            PostPageReader.Slice(posts, total)
        }!!
    }

    private fun sortExpression(order: PostPageReader.Order): String {
        val property = java.beans.Introspector.decapitalize(order.property)
        var column = SORT_COLUMNS[property] ?: throw IllegalArgumentException("Unknown post sort property")
        if (order.ignoreCase && (property == "title" || property == "content")) {
            column = "lower($column)"
        }
        return column + (if (order.ascending) " ASC" else " DESC")
    }

    private fun mapPost(row: ResultSet): PostSnapshot {
        val utc = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        return PostSnapshot(
            row.getLong("post_id"),
            row.getLong("author_id"),
            row.getString("title"),
            row.getString("content"),
            row.getString("board_type"),
            readImageUrls(row.getString("image_urls")),
            row.getTimestamp("created_at", utc).toLocalDateTime(),
            row.getTimestamp("updated_at", utc).toLocalDateTime(),
        )
    }

    private fun readImageUrls(value: String?): List<String>? {
        if (value == null || value.isEmpty()) {
            return emptyList()
        }
        try {
            return objectMapper.readValue(value, IMAGE_URLS_TYPE)
        } catch (failure: JsonProcessingException) {
            throw IllegalStateException("Failed to read post image URLs", failure)
        }
    }

    private companion object {
        private val SELECT_POST = """
            SELECT post_id, author_id, title, content, board_type, image_urls, created_at, updated_at
            FROM pawbridge_community.posts
            WHERE post_id = ? AND deleted_at IS NULL
        """.trimIndent() + "\n"

        private val SELECT_POSTS = """
            SELECT post_id, author_id, title, content, board_type, image_urls, created_at, updated_at
            FROM pawbridge_community.posts
            WHERE deleted_at IS NULL
            ORDER BY created_at DESC
        """.trimIndent() + "\n"

        private val IMAGE_URLS_TYPE = object : TypeReference<List<String>>() {}

        private val SORT_COLUMNS = mapOf(
            "postId" to "post_id",
            "authorId" to "author_id",
            "title" to "title",
            "content" to "content",
            "boardType" to "board_type",
            "imageUrls" to "image_urls",
            "deletedAt" to "deleted_at",
            "createdAt" to "created_at",
            "updatedAt" to "updated_at",
        )
    }
}
