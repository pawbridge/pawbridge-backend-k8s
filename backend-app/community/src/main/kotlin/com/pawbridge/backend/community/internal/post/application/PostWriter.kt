package com.pawbridge.backend.community.internal.post.application

import com.pawbridge.backend.community.internal.post.domain.PostToCreate

interface PostWriter {
    fun create(post: PostToCreate): PostSnapshot
}
