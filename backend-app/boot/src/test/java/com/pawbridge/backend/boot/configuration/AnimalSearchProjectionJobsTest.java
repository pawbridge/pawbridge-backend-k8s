package com.pawbridge.backend.boot.configuration;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.pawbridge.backend.animal.internal.command.infrastructure.search.PostgresqlSearchProjector;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class AnimalSearchProjectionJobsTest {
    @Test
    void refresh_processes_shelter_then_animal_dirty_pages_only() {
        PostgresqlSearchProjector projector = mock(PostgresqlSearchProjector.class);
        new AnimalSearchProjectionJobs(projector, 50).refresh();
        var order = inOrder(projector);
        order.verify(projector).refreshDirtyShelters(50);
        order.verify(projector).refreshDirtyAnimals(50);
        verifyNoMoreInteractions(projector);
    }

    @Test
    void audit_uses_integrity_pages_instead_of_dirty_only_pages() {
        PostgresqlSearchProjector projector = mock(PostgresqlSearchProjector.class);
        when(projector.refreshShelters(100)).thenReturn(1);
        when(projector.refreshAnimals(100)).thenReturn(2);
        new AnimalSearchProjectionJobs(projector, 100).audit();
        var order = inOrder(projector);
        order.verify(projector).refreshShelters(100);
        order.verify(projector).refreshAnimals(100);
        verifyNoMoreInteractions(projector);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void shelter_failure_leaves_animal_page_for_a_later_tick_without_logging_the_message(boolean audit) {
        PostgresqlSearchProjector projector = mock(PostgresqlSearchProjector.class);
        RuntimeException failure = new IllegalStateException("private-provider-message");
        if (audit) when(projector.refreshShelters(50)).thenThrow(failure);
        else when(projector.refreshDirtyShelters(50)).thenThrow(failure);
        Logger logger = (Logger) LoggerFactory.getLogger(AnimalSearchProjectionJobs.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();logger.addAppender(appender);
        try {
            AnimalSearchProjectionJobs jobs = new AnimalSearchProjectionJobs(projector, 50);
            if (audit) jobs.audit();else jobs.refresh();
            if (audit) verify(projector).refreshShelters(50);
            else verify(projector).refreshDirtyShelters(50);
            verifyNoMoreInteractions(projector);
            assertThat(appender.list).hasSize(1);
            assertThat(appender.list.get(0).getFormattedMessage()).contains("IllegalStateException")
                    .doesNotContain("private-provider-message");
            assertThat(appender.list.get(0).getThrowableProxy()).isNull();
        } finally { logger.detachAppender(appender);appender.stop(); }
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 0, 101})
    void invalid_page_size_fails_before_work(int size) {
        PostgresqlSearchProjector projector = mock(PostgresqlSearchProjector.class);
        assertThatIllegalArgumentException().isThrownBy(() -> new AnimalSearchProjectionJobs(projector, size));
        verifyNoInteractions(projector);
    }
}
