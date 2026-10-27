package com.pawbridge.backend

import com.pawbridge.backend.boot.configuration.ShutdownDiagnosticsConfiguration
import com.pawbridge.backend.boot.shutdown.InFlightRequests
import com.pawbridge.backend.boot.shutdown.ShutdownDiagnostics
import jakarta.servlet.FilterChain
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicLong
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.boot.web.context.WebServerGracefulShutdownLifecycle
import org.springframework.boot.web.servlet.FilterRegistrationBean
import org.springframework.core.Ordered
import org.springframework.mock.web.MockAsyncContext
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse

class ShutdownDiagnosticsTest {
    private val clock = Clock.fixed(Instant.parse("2031-01-01T00:00:00Z"), ZoneOffset.UTC)
    private val reports = ArrayList<String>()
    private val followUps = ArrayList<Runnable>()

    @Test
    fun `남은 요청이 없을 때__멈추면__한 줄만 남기고 후속 보고 없이 바로 넘긴다`() {
        // given
        val diagnostics = diagnostics(InFlightRequests(clock), AtomicLong(0))
        diagnostics.start()
        var released = false

        // when
        diagnostics.stop { released = true }

        // then
        assertThat(reports).containsExactly(
            "Graceful shutdown waits for 0 tracked request(s), container async in progress=0, untracked=0",
        )
        assertThat(followUps).isEmpty()
        assertThat(released).isTrue()
        assertThat(diagnostics.isRunning).isFalse()
    }

    @Test
    fun `비동기 요청이 남았을 때__멈추면__경로와 상태를 남기고 끝날 때까지 다시 보고한다`() {
        // given
        val requests = InFlightRequests(clock)
        val request = MockHttpServletRequest("GET", "/api/v1/notes/stream")
        request.isAsyncSupported = true
        requests.filter().doFilter(request, MockHttpServletResponse(), FilterChain { _, _ -> request.startAsync() })
        val containerAsync = AtomicLong(1)
        val diagnostics = diagnostics(requests, containerAsync)
        diagnostics.start()
        var released = false

        // when
        diagnostics.stop { released = true }

        // then
        assertThat(released).`as`("Diagnostics never hold the shutdown").isTrue()
        assertThat(reports).containsExactly(
            "Graceful shutdown waits for 1 tracked request(s), container async in progress=1, untracked=0: " +
                "GET /api/v1/notes/stream age=0s async",
        )
        assertThat(followUps).hasSize(1)

        // when
        (request.asyncContext as MockAsyncContext).complete()
        containerAsync.set(0)
        followUps.single().run()

        // then
        assertThat(reports.last()).isEqualTo("Graceful shutdown requests drained")
    }

    @Test
    fun `컨테이너에만 비동기 요청이 남았을 때__멈추면__추적 밖의 요청도 대기로 보고 상한까지만 보고한다`() {
        // given
        val diagnostics = diagnostics(InFlightRequests(clock), AtomicLong(1), maxReports = 2)
        diagnostics.start()

        // when
        diagnostics.stop {}
        followUps.single().run()

        // then
        assertThat(reports).hasSize(3).allSatisfy { report ->
            assertThat(report).isEqualTo(
                "Graceful shutdown waits for 0 tracked request(s), container async in progress=1, untracked=0",
            )
        }
    }

    @Test
    fun `웹 서버 생명주기가 있을 때__단계를 보면__graceful shutdown 바로 앞에서 멈춘다`() {
        // given
        val diagnostics = diagnostics(InFlightRequests(clock), AtomicLong(0))

        // when
        val phase = diagnostics.phase

        // then
        assertThat(phase).isEqualTo(WebServerGracefulShutdownLifecycle.SMART_LIFECYCLE_PHASE + 1)
    }

    @Test
    fun `설정을 올렸을 때__기동하면__추적 필터가 가장 앞에 등록되고 진단 생명주기가 하나 있다`() {
        // given
        val runner = ApplicationContextRunner().withUserConfiguration(ShutdownDiagnosticsConfiguration::class.java)

        // when
        runner.run { context ->
            // then
            val registration = context.getBean("inFlightRequestsFilter") as FilterRegistrationBean<*>
            assertThat(registration.order).isEqualTo(Ordered.HIGHEST_PRECEDENCE)
            assertThat(context).hasSingleBean(ShutdownDiagnostics::class.java)
        }
    }

    private fun diagnostics(requests: InFlightRequests, containerAsync: AtomicLong, maxReports: Int = 5) =
        ShutdownDiagnostics(
            requests,
            { containerAsync.get() },
            maxReports = maxReports,
            report = { message -> reports.add(message) },
            background = { work -> followUps.add(work) },
            pause = {},
        )
}
