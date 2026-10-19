package com.pawbridge.backend.boot.configuration;

import com.pawbridge.backend.community.internal.homevideo.application.HomeVideoReader;
import com.pawbridge.backend.community.internal.homevideo.application.ReadHomeVideos;
import com.pawbridge.backend.community.internal.homevideo.infrastructure.JdbcHomeVideoReader;
import com.pawbridge.backend.community.internal.homevideo.web.HomeVideoController;
import com.pawbridge.backend.community.internal.homevideo.web.HomeVideoExceptionHandler;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

@Configuration(proxyBeanMethods = false)
public class HomeVideoReadConfiguration {
    @Bean
    Clock readClock() { return Clock.systemUTC(); }

    @Bean
    HomeVideoReader homeVideoReader(@Qualifier("readJdbc") JdbcTemplate jdbc,
                                   @Qualifier("readOnlyTransaction") TransactionTemplate transaction) {
        return new JdbcHomeVideoReader(jdbc, transaction);
    }

    @Bean
    ReadHomeVideos readHomeVideos(HomeVideoReader reader, @Qualifier("readClock") Clock clock) {
        return new ReadHomeVideos(reader, clock);
    }

    @Bean
    HomeVideoController homeVideoController(ReadHomeVideos videos) { return new HomeVideoController(videos); }

    @Bean
    HomeVideoExceptionHandler homeVideoExceptionHandler() { return new HomeVideoExceptionHandler(); }
}
