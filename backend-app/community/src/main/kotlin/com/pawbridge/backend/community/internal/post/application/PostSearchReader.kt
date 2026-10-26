package com.pawbridge.backend.community.internal.post.application

interface PostSearchReader {
    fun search(keyword: String?): List<PostSnapshot>
}
