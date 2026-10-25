package com.pawbridge.backend.community.internal.post.application

import java.time.LocalDateTime
import java.util.Collections

// A Java record whose compact constructor copies `imageUrls` into an unmodifiable list (null stays null).
// Jackson serializes this class directly. It used to read the record components, now it reads the JavaBean
// getters (getPostId() ...), which yield the same property names, order and null handling. The record-style
// accessors (postId() ...), equals, hashCode and toString are kept for Java callers and tests.
class PostDetails(
    postId: Long,
    authorId: Long,
    authorNickname: String?,
    title: String?,
    content: String?,
    boardType: String?,
    imageUrls: List<String?>?,
    createdAt: LocalDateTime?,
    updatedAt: LocalDateTime?,
) {
    // The properties are declared in the body in record component order so that the field order, which Jackson
    // uses for the JSON property order, matches the record.
    val postId: Long = postId
    val authorId: Long = authorId
    val authorNickname: String? = authorNickname
    val title: String? = title
    val content: String? = content
    val boardType: String? = boardType
    val imageUrls: List<String?>? =
        if (imageUrls != null) Collections.unmodifiableList(ArrayList(imageUrls)) else null
    val createdAt: LocalDateTime? = createdAt
    val updatedAt: LocalDateTime? = updatedAt

    fun postId(): Long = postId

    fun authorId(): Long = authorId

    fun authorNickname(): String? = authorNickname

    fun title(): String? = title

    fun content(): String? = content

    fun boardType(): String? = boardType

    fun imageUrls(): List<String?>? = imageUrls

    fun createdAt(): LocalDateTime? = createdAt

    fun updatedAt(): LocalDateTime? = updatedAt

    override fun equals(other: Any?): Boolean =
        other is PostDetails &&
            postId == other.postId &&
            authorId == other.authorId &&
            authorNickname == other.authorNickname &&
            title == other.title &&
            content == other.content &&
            boardType == other.boardType &&
            imageUrls == other.imageUrls &&
            createdAt == other.createdAt &&
            updatedAt == other.updatedAt

    override fun hashCode(): Int {
        var result = postId.hashCode()
        result = 31 * result + authorId.hashCode()
        result = 31 * result + (authorNickname?.hashCode() ?: 0)
        result = 31 * result + (title?.hashCode() ?: 0)
        result = 31 * result + (content?.hashCode() ?: 0)
        result = 31 * result + (boardType?.hashCode() ?: 0)
        result = 31 * result + (imageUrls?.hashCode() ?: 0)
        result = 31 * result + (createdAt?.hashCode() ?: 0)
        result = 31 * result + (updatedAt?.hashCode() ?: 0)
        return result
    }

    override fun toString(): String =
        "PostDetails[postId=$postId, authorId=$authorId, authorNickname=$authorNickname, title=$title, " +
            "content=$content, boardType=$boardType, imageUrls=$imageUrls, createdAt=$createdAt, " +
            "updatedAt=$updatedAt]"
}
