package com.pawbridge.backend.community.internal.post.web

@JvmRecord
data class ApiResponse<T>(val code: Int, val message: String?, val data: T?)
