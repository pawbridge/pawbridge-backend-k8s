package com.pawbridge.backend.community.internal.homevideo.application

import java.util.UUID

interface HomeVideoManagement {

    fun board(): Board

    fun preview(url: String?): Metadata

    fun save(id: UUID?, request: Save): Board

    fun publish(id: UUID, request: Publication): Board

    fun reorder(request: Order): Board

    fun recheck(id: UUID, revision: Long): Board

    fun refresh()

    @JvmRecord
    data class Metadata(
        val videoId: String,
        val title: String?,
        val channelTitle: String?,
        val thumbnailUrl: String?,
        val durationSeconds: Long,
        val available: Boolean,
    )

    @JvmRecord
    data class Board(val revision: Long, val videos: List<HomeVideo>)

    @JvmRecord
    data class Save(val url: String?, val published: Boolean, val revision: Long)

    @JvmRecord
    data class Publication(val published: Boolean, val revision: Long)

    @JvmRecord
    data class Order(val ids: List<UUID>?, val revision: Long)
}
