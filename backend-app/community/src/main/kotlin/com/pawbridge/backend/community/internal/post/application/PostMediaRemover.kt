package com.pawbridge.backend.community.internal.post.application

/** Attempts each stored media URL independently; individual failures do not abort deletion. */
interface PostMediaRemover {
    fun remove(imageUrls: List<String?>?)
}
