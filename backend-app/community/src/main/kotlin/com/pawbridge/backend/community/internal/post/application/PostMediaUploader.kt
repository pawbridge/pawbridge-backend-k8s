package com.pawbridge.backend.community.internal.post.application

interface PostMediaUploader {
    fun upload(uploads: List<PostUpload>?): List<String>
}
