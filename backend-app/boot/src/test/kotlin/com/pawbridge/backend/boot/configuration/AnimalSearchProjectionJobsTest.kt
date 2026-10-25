package com.pawbridge.backend.boot.configuration

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.pawbridge.backend.animal.internal.command.infrastructure.search.PostgresqlSearchProjector
import io.mockk.confirmVerified
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifySequence
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatIllegalArgumentException
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.slf4j.LoggerFactory

class AnimalSearchProjectionJobsTest {
    @Test
    fun `더티 페이지 갱신이 주어졌을 때__refresh하면__보호소 다음 동물 순서로 더티 페이지만 처리한다`() {
        // given
        val projector: PostgresqlSearchProjector = mockk<PostgresqlSearchProjector>()
        val jobs = AnimalSearchProjectionJobs(projector, 50)

        every { projector.refreshDirtyShelters(any()) } returns 0
        every { projector.refreshDirtyAnimals(any()) } returns 0

        // when
        jobs.refresh()

        // then
        verifySequence {
            projector.refreshDirtyShelters(50)
            projector.refreshDirtyAnimals(50)
        }
    }

    @Test
    fun `감사가 주어졌을 때__audit하면__더티 전용이 아니라 무결성 페이지를 쓴다`() {
        // given
        val projector: PostgresqlSearchProjector = mockk<PostgresqlSearchProjector>()
        val jobs = AnimalSearchProjectionJobs(projector, 100)

        every { projector.refreshShelters(any()) } returns 1
        every { projector.refreshAnimals(any()) } returns 2

        // when
        jobs.audit()

        // then
        verifySequence {
            projector.refreshShelters(100)
            projector.refreshAnimals(100)
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `보호소 갱신이 실패할 때__실행하면__메시지를 남기지 않고 동물 페이지는 다음 틱으로 미룬다`(audit: Boolean) {
        // given
        val projector: PostgresqlSearchProjector = mockk<PostgresqlSearchProjector>()
        val failure: RuntimeException = IllegalStateException("private-provider-message")
        if (audit) {
            every { projector.refreshShelters(any()) } throws failure
        } else {
            every { projector.refreshDirtyShelters(any()) } throws failure
        }
        val logger = LoggerFactory.getLogger(AnimalSearchProjectionJobs::class.java) as Logger
        val appender = ListAppender<ILoggingEvent>()
        appender.start()
        logger.addAppender(appender)
        try {
            val jobs = AnimalSearchProjectionJobs(projector, 50)

            // when
            if (audit) {
                jobs.audit()
            } else {
                jobs.refresh()
            }

            // then
            if (audit) {
                verify(exactly = 1) { projector.refreshShelters(50) }
            } else {
                verify(exactly = 1) { projector.refreshDirtyShelters(50) }
            }
            confirmVerified(projector)
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
    fun `잘못된 페이지 크기가 주어졌을 때__만들면__작업 전에 실패한다`(size: Int) {
        // given
        val projector: PostgresqlSearchProjector = mockk<PostgresqlSearchProjector>()

        // when, then
        assertThatIllegalArgumentException().isThrownBy { AnimalSearchProjectionJobs(projector, size) }
        confirmVerified(projector)
    }
}
