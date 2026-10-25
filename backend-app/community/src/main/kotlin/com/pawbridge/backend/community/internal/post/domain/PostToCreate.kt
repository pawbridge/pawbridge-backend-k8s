package com.pawbridge.backend.community.internal.post.domain

import java.util.Collections

// A Java record with a compact constructor that copies `imageUrls` into an unmodifiable list.
// @JvmRecord cannot transform a constructor argument, so this is a plain class that keeps the
// record-style accessor names, equals, hashCode and toString.
class PostToCreate(
    private val authorId: Long,
    private val title: String?,
    private val content: String?,
    private val boardType: PostBoardType,
    imageUrls: List<String?>,
) {
    private val imageUrls: List<String?> = Collections.unmodifiableList(ArrayList(imageUrls))

    fun authorId(): Long = authorId

    fun title(): String? = title

    fun content(): String? = content

    fun boardType(): PostBoardType = boardType

    fun imageUrls(): List<String?> = imageUrls

    override fun equals(other: Any?): Boolean =
        other is PostToCreate &&
            authorId == other.authorId &&
            title == other.title &&
            content == other.content &&
            boardType == other.boardType &&
            imageUrls == other.imageUrls

    override fun hashCode(): Int {
        var result = authorId.hashCode()
        result = 31 * result + (title?.hashCode() ?: 0)
        result = 31 * result + (content?.hashCode() ?: 0)
        result = 31 * result + boardType.hashCode()
        result = 31 * result + imageUrls.hashCode()
        return result
    }

    override fun toString(): String =
        "PostToCreate[authorId=$authorId, title=$title, content=$content, boardType=$boardType, imageUrls=$imageUrls]"
}
