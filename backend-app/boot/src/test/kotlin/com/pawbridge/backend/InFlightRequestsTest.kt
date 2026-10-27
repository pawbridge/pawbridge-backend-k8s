package com.pawbridge.backend

import com.pawbridge.backend.boot.shutdown.InFlightRequests
import jakarta.servlet.AsyncEvent
import jakarta.servlet.FilterChain
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockAsyncContext
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse

class InFlightRequestsTest {
    private val clock = MutableClock()

    @Test
    fun `동기 요청일 때__필터를 지나면__처리 중에만 보이고 끝나면 사라진다`() {
        // given
        val requests = InFlightRequests(clock)
        val request = MockHttpServletRequest("GET", "/api/v1/posts/read")
        request.queryString = "token=hidden"
        var during: InFlightRequests.Snapshot? = null

        // when
        requests.filter().doFilter(request, MockHttpServletResponse(), FilterChain { _, _ -> during = requests.snapshot() })

        // then
        assertThat(during!!.entries).singleElement().satisfies({ entry ->
            assertThat(entry.method).isEqualTo("GET")
            assertThat(entry.path).isEqualTo("/api/v1/posts/read")
            assertThat(entry.async).isFalse()
        })
        assertThat(requests.snapshot().entries).isEmpty()
    }

    @Test
    fun `비동기 요청일 때__첫 디스패치가 끝나면__컨테이너가 완료를 알릴 때까지 남고 시간 초과는 기록된다`() {
        // given
        val requests = InFlightRequests(clock)
        val request = MockHttpServletRequest("GET", "/api/v1/notes/stream")
        request.isAsyncSupported = true
        requests.filter().doFilter(request, MockHttpServletResponse(), FilterChain { _, _ -> request.startAsync() })
        val context = request.asyncContext as MockAsyncContext

        // when
        clock.advance(30)
        context.listeners.forEach { listener -> listener.onTimeout(AsyncEvent(context)) }

        // then
        assertThat(requests.snapshot().entries).singleElement().satisfies({ entry ->
            assertThat(entry.async).isTrue()
            assertThat(entry.timedOutAt).isEqualTo(clock.instant())
        })

        // when
        context.complete()

        // then
        assertThat(requests.snapshot().entries).isEmpty()
    }

    @Test
    fun `한도만큼 남아 있을 때__새 요청이 오면__추적하지 않고 수만 센다`() {
        // given
        val requests = InFlightRequests(clock, 1)
        val held = MockHttpServletRequest("GET", "/api/v1/notes/stream")
        held.isAsyncSupported = true
        requests.filter().doFilter(held, MockHttpServletResponse(), FilterChain { _, _ -> held.startAsync() })

        // when
        requests.filter().doFilter(MockHttpServletRequest("GET", "/api/v1/animals"), MockHttpServletResponse(), FilterChain { _, _ -> })

        // then
        val snapshot = requests.snapshot()
        assertThat(snapshot.entries.map { it.path }).containsExactly("/api/v1/notes/stream")
        assertThat(snapshot.untracked).isEqualTo(1)
    }

    private class MutableClock : Clock() {
        @Volatile
        private var now: Instant = Instant.parse("2031-01-01T00:00:00Z")

        override fun getZone(): ZoneId = ZoneOffset.UTC

        override fun withZone(zone: ZoneId): Clock = this

        override fun instant(): Instant = now

        fun advance(seconds: Long) {
            now = now.plusSeconds(seconds)
        }
    }
}
