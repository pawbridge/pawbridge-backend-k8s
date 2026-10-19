package com.pawbridge.backend.community.internal.homevideo.application;

import java.time.Instant;
import java.util.UUID;

public record HomeVideo(
        UUID id,
        String videoId,
        boolean published,
        int position,
        Instant createdAt,
        String title,
        String channelTitle,
        String thumbnailUrl,
        Long durationSeconds,
        boolean available,
        Instant checkedAt) {
}
