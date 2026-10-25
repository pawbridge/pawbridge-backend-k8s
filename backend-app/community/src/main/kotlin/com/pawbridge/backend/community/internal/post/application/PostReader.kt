package com.pawbridge.backend.community.internal.post.application

import java.util.Optional

interface PostReader {
    fun findById(postId: Long): Optional<PostSnapshot>
}
