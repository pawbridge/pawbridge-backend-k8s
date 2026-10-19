package com.pawbridge.backend.community.internal.note.application;

import com.pawbridge.backend.community.internal.note.application.NoteNotifications.Notification;

@FunctionalInterface
public interface NoteDeliveryHints {
    void publish(long memberId, Notification notification);
}
