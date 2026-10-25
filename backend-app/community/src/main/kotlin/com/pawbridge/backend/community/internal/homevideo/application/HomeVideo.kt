package com.pawbridge.backend.community.internal.homevideo.application

import java.time.Instant
import java.util.UUID

@JvmRecord
data class HomeVideo(
    val id: UUID,
    val videoId: String,
    val published: Boolean,
    val position: Int,
    val createdAt: Instant,
    val title: String?,
    val channelTitle: String?,
    val thumbnailUrl: String?,
    val durationSeconds: Long?,
    val available: Boolean,
    val checkedAt: Instant?,
)
