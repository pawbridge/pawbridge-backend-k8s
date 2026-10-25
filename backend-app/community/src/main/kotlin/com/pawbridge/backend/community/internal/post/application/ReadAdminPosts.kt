package com.pawbridge.backend.community.internal.post.application

class ReadAdminPosts(
    private val posts: PostPageReader,
    private val details: ReadPost,
) {

    fun read(request: PostPageReader.Request): Page {
        val page = posts.findPage(request)
        return Page(page.content().map { post -> details.details(post) }, page.total())
    }

    // A Java record whose compact constructor copies `content` with List.copyOf (immutable, rejects nulls).
    class Page(
        content: List<PostDetails>,
        private val total: Long,
    ) {
        private val content: List<PostDetails> = java.util.List.copyOf(content)

        fun content(): List<PostDetails> = content

        fun total(): Long = total

        override fun equals(other: Any?): Boolean =
            other is Page && content == other.content && total == other.total

        override fun hashCode(): Int {
            var result = content.hashCode()
            result = 31 * result + total.hashCode()
            return result
        }

        override fun toString(): String = "Page[content=$content, total=$total]"
    }
}
