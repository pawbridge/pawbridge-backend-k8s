package com.pawbridge.backend.community.internal.post.application

import java.time.LocalDateTime
import java.util.Collections

// A Java record whose compact constructor copies `imageUrls` into an unmodifiable list (null stays null).
// @JvmRecord cannot transform a constructor argument, so this is a plain class that keeps the record-style
// accessor names, equals, hashCode and toString.
class PostSnapshot(
    private val postId: Long,
    private val authorId: Long,
    private val title: String?,
    private val content: String?,
    private val boardType: String?,
    imageUrls: List<String?>?,
    private val createdAt: LocalDateTime?,
    private val updatedAt: LocalDateTime?,
) {
    private val imageUrls: List<String?>? =
        if (imageUrls != null) Collections.unmodifiableList(ArrayList(imageUrls)) else null

    fun postId(): Long = postId

    fun authorId(): Long = authorId

    fun title(): String? = title

    fun content(): String? = content

    fun boardType(): String? = boardType

    fun imageUrls(): List<String?>? = imageUrls

    fun createdAt(): LocalDateTime? = createdAt

    fun updatedAt(): LocalDateTime? = updatedAt

    override fun equals(other: Any?): Boolean =
        other is PostSnapshot &&
            postId == other.postId &&
            authorId == other.authorId &&
            title == other.title &&
            content == other.content &&
            boardType == other.boardType &&
            imageUrls == other.imageUrls &&
            createdAt == other.createdAt &&
            updatedAt == other.updatedAt

    override fun hashCode(): Int {
        var result = postId.hashCode()
        result = 31 * result + authorId.hashCode()
        result = 31 * result + (title?.hashCode() ?: 0)
        result = 31 * result + (content?.hashCode() ?: 0)
        result = 31 * result + (boardType?.hashCode() ?: 0)
        result = 31 * result + (imageUrls?.hashCode() ?: 0)
        result = 31 * result + (createdAt?.hashCode() ?: 0)
        result = 31 * result + (updatedAt?.hashCode() ?: 0)
        return result
    }

    override fun toString(): String =
        "PostSnapshot[postId=$postId, authorId=$authorId, title=$title, content=$content, " +
            "boardType=$boardType, imageUrls=$imageUrls, createdAt=$createdAt, updatedAt=$updatedAt]"
}
