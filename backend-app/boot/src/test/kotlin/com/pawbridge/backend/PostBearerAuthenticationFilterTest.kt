package com.pawbridge.backend

import com.pawbridge.backend.boot.security.PostBearerAuthenticationFilter
import com.pawbridge.backend.boot.security.PostTokenAuthenticator
import io.jsonwebtoken.Jwts
import io.mockk.spyk
import io.mockk.verify
import jakarta.servlet.FilterChain
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.web.context.RequestAttributeSecurityContextRepository
import org.springframework.security.web.util.matcher.RequestMatcher
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Date
import java.util.concurrent.atomic.AtomicBoolean
import java.util.stream.Stream

class PostBearerAuthenticationFilterTest {
    private val filter = PostBearerAuthenticationFilter(
        PostTokenAuthenticator(F3TestInputs.KEY),
        RequestMatcher { true },
    )

    @ParameterizedTest
    @MethodSource("verifiedRoles")
    fun `검증된 역할이 주어졌을 때__보호 요청을 필터링하면__Long 주체와 정확한 관리자 권한만 유지한다`(
        role: String,
        authorities: List<String>,
    ) {
        // given
        val request = MockHttpServletRequest("GET", "/api/v1/admin/users/stats/total-users")
        request.addHeader("Authorization", "Bearer " + F3TestInputs.token(7, role))

        // when
        filter.doFilter(
            request,
            MockHttpServletResponse(),
            FilterChain { _, _ ->
                val identity = SecurityContextHolder.getContext().authentication!!
                assertThat(identity.isAuthenticated).isTrue()
                assertThat(identity.principal).isInstanceOf(Long::class.javaObjectType).isEqualTo(7L)
                assertThat(identity.credentials).isNull()
                assertThat(identity.details).isInstanceOf(Instant::class.java)
                assertThat(identity.authorities.map { it.authority }).containsExactlyElementsOf(authorities)
            },
        )

        // then
        assertThat(SecurityContextHolder.getContext().authentication).isNull()
    }

    @Test
    fun `인증 뒤 업무 실패가 주어졌을 때__체인이 던지면__전파하고 인증 응답 없이 컨텍스트를 비운다`() {
        // given
        val request = MockHttpServletRequest("GET", "/api/v1/admin/users/stats/total-users")
        request.addHeader("Authorization", "Bearer " + F3TestInputs.token(7, "ROLE_ADMIN"))
        val response = MockHttpServletResponse()
        val failure = IllegalStateException("synthetic-private-query")

        // when
        val thrown = assertThatThrownBy {
            filter.doFilter(
                request,
                response,
                FilterChain { _, _ ->
                    assertThat(SecurityContextHolder.getContext().authentication!!.principal).isEqualTo(7L)
                    throw failure
                },
            )
        }

        // then
        thrown.isSameAs(failure)
        assertThat(response.status).isEqualTo(200)
        assertThat(response.contentAsString).isEmpty()
        assertThat(SecurityContextHolder.getContext().authentication).isNull()
    }

    @Test
    fun `검증된 스트림 만료가 주어졌을 때__필터링하면__Long 신원과 Instant만 요청 컨텍스트에 저장한다`() {
        // given
        val repository = spyk(RequestAttributeSecurityContextRepository())
        val filter = PostBearerAuthenticationFilter(
            PostTokenAuthenticator(F3TestInputs.KEY),
            RequestMatcher { true },
            RequestMatcher { request -> request.requestURI == "/api/v1/notes/stream" },
            repository,
        )
        val expiry = Instant.now().plusSeconds(120).truncatedTo(ChronoUnit.SECONDS)
        val token = Jwts.builder()
            .claim("userId", 7)
            .claim("role", "ROLE_USER")
            .expiration(Date.from(expiry))
            .signWith(F3TestInputs.KEY)
            .compact()
        val request = MockHttpServletRequest("GET", "/api/v1/notes/stream")
        request.addHeader("Authorization", "Bearer $token")
        request.addHeader("X-Auth-Expires-At", Long.MAX_VALUE)
        val response = MockHttpServletResponse()

        // when
        filter.doFilter(
            request,
            response,
            FilterChain { _, _ ->
                val identity = SecurityContextHolder.getContext().authentication!!
                assertThat(identity.principal).isEqualTo(7L)
                assertThat(identity.details).isEqualTo(expiry)
                assertThat(identity.credentials).isNull()
                assertThat(repository.loadDeferredContext(request).get().authentication).isSameAs(identity)
            },
        )

        // then
        verify(exactly = 1) { repository.saveContext(any(), refEq(request), refEq(response)) }
        assertThat(request.getSession(false)).isNull()
        assertThat(
            repository.loadDeferredContext(MockHttpServletRequest("GET", "/api/v1/notes/stream")).get().authentication,
        ).isNull()
        assertThat(SecurityContextHolder.getContext().authentication).isNull()
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `만료 없는 토큰이 주어졌을 때__REST나 스트림을 필터링하면__스트림만 거부하고 저장하지 않는다`(
        stream: Boolean,
    ) {
        // given
        val repository = spyk(RequestAttributeSecurityContextRepository())
        val filter = PostBearerAuthenticationFilter(
            PostTokenAuthenticator(F3TestInputs.KEY),
            RequestMatcher { true },
            RequestMatcher { request -> request.requestURI == "/api/v1/notes/stream" },
            repository,
        )
        val token = Jwts.builder().claim("userId", 7).claim("role", "ROLE_UNKNOWN").signWith(F3TestInputs.KEY).compact()
        val request = MockHttpServletRequest("GET", if (stream) "/api/v1/notes/stream" else "/api/v1/notes")
        request.addHeader("Authorization", "Bearer $token")
        val response = MockHttpServletResponse()
        val reached = AtomicBoolean()

        // when
        filter.doFilter(
            request,
            response,
            FilterChain { _, _ ->
                reached.set(true)
                assertThat(SecurityContextHolder.getContext().authentication!!.details).isNull()
            },
        )

        // then
        assertThat(reached.get()).isEqualTo(!stream)
        assertThat(response.status).isEqualTo(if (stream) 401 else 200)
        verify(exactly = 0) { repository.saveContext(any(), any(), any()) }
        assertThat(SecurityContextHolder.getContext().authentication).isNull()
        assertThat(request.getSession(false)).isNull()
    }

    companion object {
        @JvmStatic
        fun verifiedRoles(): Stream<Arguments> {
            return Stream.of(
                Arguments.of("ROLE_ADMIN", listOf("ROLE_ADMIN")),
                Arguments.of("ROLE_UNKNOWN", listOf<String>()),
            )
        }
    }
}
