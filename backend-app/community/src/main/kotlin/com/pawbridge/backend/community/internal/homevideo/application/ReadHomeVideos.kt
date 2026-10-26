package com.pawbridge.backend.community.internal.homevideo.application

import java.time.Clock
import java.time.Duration

class ReadHomeVideos(
    private val reader: HomeVideoReader,
    private val clock: Clock,
) {

    fun read(): List<HomeVideo> {
        val cutoff = clock.instant().minus(METADATA_LIFETIME)
        return reader.read(cutoff)
    }

    private companion object {
        private val METADATA_LIFETIME: Duration = Duration.ofDays(29)
    }
}
