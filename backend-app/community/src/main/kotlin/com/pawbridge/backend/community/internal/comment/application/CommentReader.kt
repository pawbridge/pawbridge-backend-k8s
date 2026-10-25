package com.pawbridge.backend.community.internal.comment.application

import java.util.Optional

interface CommentReader {
    fun findById(commentId: Long): Optional<CommentSnapshot>
}
