package com.pawbridge.backend.community.internal.post.application

interface PostListReader {
    fun findAll(): List<PostSnapshot>
}
