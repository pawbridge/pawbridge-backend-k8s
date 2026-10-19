package com.pawbridge.backend.community.internal.homevideo.application;

import java.time.Instant;
import java.util.List;

public interface HomeVideoReader {
    List<HomeVideo> read(Instant cutoff);
}
