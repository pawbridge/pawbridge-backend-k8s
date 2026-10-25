package com.pawbridge.backend.community.internal.homevideo.application

import java.time.Instant

interface HomeVideoReader {

    fun read(cutoff: Instant): List<HomeVideo>
}
