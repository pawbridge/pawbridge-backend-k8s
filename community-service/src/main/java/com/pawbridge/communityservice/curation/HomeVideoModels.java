package com.pawbridge.communityservice.curation;

import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class HomeVideoModels {
    private HomeVideoModels() {}
    public record Metadata(String videoId, String title, String channelTitle, String thumbnailUrl,
                           long durationSeconds, boolean available) {}
    public record Video(UUID id, String videoId, boolean published, int position, Instant createdAt,
                        String title, String channelTitle, String thumbnailUrl, Long durationSeconds,
                        boolean available, Instant checkedAt) {}
    public record Board(long revision, List<Video> videos) {}
    public record Preview(@NotBlank @Size(max = 2048) String url) {}
    public record Save(@NotBlank @Size(max = 2048) String url, boolean published, @Min(0) long revision) {}
    public record Publication(boolean published, @Min(0) long revision) {}
    public record Order(@NotNull @Size(max = 3) List<@NotNull UUID> ids, @Min(0) long revision) {}
}
