package com.pawbridge.backend.community.internal.note.application

import com.pawbridge.backend.community.internal.note.application.NoteNotifications.Notification

fun interface NoteDeliveryHints {
    fun publish(
        memberId: Long,
        notification: Notification,
    )
}
