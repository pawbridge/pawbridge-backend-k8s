package com.pawbridge.backend;

import static org.assertj.core.api.Assertions.assertThat;

import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.web.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.config.ScheduledTaskHolder;

class PawBridgeApplicationTest {
    @Test
    void given__default_configuration__when__context_starts__then__external_resources_are_absent() {
        ConfigurableApplicationContext context = SpringApplication.run(PawBridgeApplication.class);
        try (context) {
            assertThat(context.isActive()).isTrue();
            assertThat(context).isNotInstanceOf(WebServerApplicationContext.class);
            assertThat(context.getBeansOfType(DataSource.class)).isEmpty();
            assertThat(context.getBeansOfType(TaskScheduler.class)).isEmpty();
            assertThat(context.getBeansOfType(ScheduledTaskHolder.class)).isEmpty();
        }
        assertThat(context.isActive()).isFalse();
    }
}
