package com.pawbridge.backend.boot.configuration

import com.pawbridge.backend.community.internal.homevideo.application.HomeVideoReader
import com.pawbridge.backend.community.internal.homevideo.application.ReadHomeVideos
import com.pawbridge.backend.community.internal.homevideo.infrastructure.JdbcHomeVideoReader
import com.pawbridge.backend.community.internal.homevideo.web.HomeVideoController
import com.pawbridge.backend.community.internal.homevideo.web.HomeVideoExceptionHandler
import java.time.Clock
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.support.TransactionTemplate

@Configuration(proxyBeanMethods = false)
class HomeVideoReadConfiguration {
    @Bean
    fun readClock(): Clock {
        return Clock.systemUTC()
    }

    @Bean
    fun homeVideoReader(
        @Qualifier("readJdbc") jdbc: JdbcTemplate,
        @Qualifier("readOnlyTransaction") transaction: TransactionTemplate,
    ): HomeVideoReader {
        return JdbcHomeVideoReader(jdbc, transaction)
    }

    @Bean
    fun readHomeVideos(reader: HomeVideoReader, @Qualifier("readClock") clock: Clock): ReadHomeVideos {
        return ReadHomeVideos(reader, clock)
    }

    @Bean
    fun homeVideoController(videos: ReadHomeVideos): HomeVideoController {
        return HomeVideoController(videos)
    }

    @Bean
    fun homeVideoExceptionHandler(): HomeVideoExceptionHandler {
        return HomeVideoExceptionHandler()
    }
}
