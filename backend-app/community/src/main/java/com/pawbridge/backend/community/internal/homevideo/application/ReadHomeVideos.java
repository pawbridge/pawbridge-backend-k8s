package com.pawbridge.backend.community.internal.homevideo.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

public final class ReadHomeVideos {
    private static final Duration METADATA_LIFETIME = Duration.ofDays(29);

    private final HomeVideoReader reader;
    private final Clock clock;

    public ReadHomeVideos(HomeVideoReader reader, Clock clock) {
        this.reader = reader;
        this.clock = clock;
    }

    public List<HomeVideo> read() {
        Instant cutoff = clock.instant().minus(METADATA_LIFETIME);
        return reader.read(cutoff);
    }
}
