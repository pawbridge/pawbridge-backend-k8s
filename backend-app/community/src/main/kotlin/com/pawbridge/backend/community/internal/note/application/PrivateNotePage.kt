package com.pawbridge.backend.community.internal.note.application

@JvmRecord
data class PrivateNotePage(
    val content: List<PrivateNoteView>,
    val totalElements: Long,
    val totalPages: Int,
    val number: Int,
    val size: Int,
)
