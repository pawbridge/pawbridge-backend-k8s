package com.pawbridge.backend.boot.configuration

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.pawbridge.backend.animal.internal.command.infrastructure.search.PostgresqlSearchProjector
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatIllegalArgumentException
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.mockito.Mockito.inOrder
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when` as whenever
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.verifyNoMoreInteractions
import org.slf4j.LoggerFactory

class AnimalSearchProjectionJobsTest {
    @Test
    fun refresh_processes_shelter_then_animal_dirty_pages_only() {
        val projector: PostgresqlSearchProjector = mock(PostgresqlSearchProjector::class.java)
        AnimalSearchProjectionJobs(projector, 50).refresh()
        val order = inOrder(projector)
        order.verify(projector).refreshDirtyShelters(50)
        order.verify(projector).refreshDirtyAnimals(50)
        verifyNoMoreInteractions(projector)
    }

    @Test
    fun audit_uses_integrity_pages_instead_of_dirty_only_pages() {
        val projector: PostgresqlSearchProjector = mock(PostgresqlSearchProjector::class.java)
        whenever(projector.refreshShelters(100)).thenReturn(1)
        whenever(projector.refreshAnimals(100)).thenReturn(2)
        AnimalSearchProjectionJobs(projector, 100).audit()
        val order = inOrder(projector)
        order.verify(projector).refreshShelters(100)
        order.verify(projector).refreshAnimals(100)
        verifyNoMoreInteractions(projector)
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun shelter_failure_leaves_animal_page_for_a_later_tick_without_logging_the_message(audit: Boolean) {
        val projector: PostgresqlSearchProjector = mock(PostgresqlSearchProjector::class.java)
        val failure: RuntimeException = IllegalStateException("private-provider-message")
        if (audit) {
            whenever(projector.refreshShelters(50)).thenThrow(failure)
        } else {
            whenever(projector.refreshDirtyShelters(50)).thenThrow(failure)
        }
        val logger = LoggerFactory.getLogger(AnimalSearchProjectionJobs::class.java) as Logger
        val appender = ListAppender<ILoggingEvent>()
        appender.start()
        logger.addAppender(appender)
        try {
            val jobs = AnimalSearchProjectionJobs(projector, 50)
            if (audit) {
                jobs.audit()
            } else {
                jobs.refresh()
            }
            if (audit) {
                verify(projector).refreshShelters(50)
            } else {
                verify(projector).refreshDirtyShelters(50)
            }
            verifyNoMoreInteractions(projector)
            assertThat(appender.list).hasSize(1)
            assertThat(appender.list[0].formattedMessage).contains("IllegalStateException")
                .doesNotContain("private-provider-message")
            assertThat(appender.list[0].throwableProxy).isNull()
        } finally {
            logger.detachAppender(appender)
            appender.stop()
        }
    }

    @ParameterizedTest
    @ValueSource(ints = [-1, 0, 101])
    fun invalid_page_size_fails_before_work(size: Int) {
        val projector: PostgresqlSearchProjector = mock(PostgresqlSearchProjector::class.java)
        assertThatIllegalArgumentException().isThrownBy { AnimalSearchProjectionJobs(projector, size) }
        verifyNoInteractions(projector)
    }
}
