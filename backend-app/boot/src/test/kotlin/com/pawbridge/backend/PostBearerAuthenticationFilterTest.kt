package com.pawbridge.backend

import com.pawbridge.backend.boot.security.PostBearerAuthenticationFilter
import com.pawbridge.backend.boot.security.PostTokenAuthenticator
import com.pawbridge.backend.support.anyNonNull
import com.pawbridge.backend.support.sameValue
import io.jsonwebtoken.Jwts
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import org.junit.jupiter.params.provider.ValueSource
import org.mockito.Mockito.never
import org.mockito.Mockito.spy
import org.mockito.Mockito.verify
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.core.context.SecurityContext
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
    fun given__verified_role__when__filtering_a_protected_request__then__keep_long_principal_null_credentials_and_only_exact_admin_authority(
        role: String,
        authorities: List<String>,
    ) {
        val request = MockHttpServletRequest("GET", "/api/v1/admin/users/stats/total-users")
        request.addHeader("Authorization", "Bearer " + F3TestInputs.token(7, role))

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

        assertThat(SecurityContextHolder.getContext().authentication).isNull()
    }

    @Test
    fun given__business_failure_after_authentication__when__the_chain_throws__then__propagate_it_and_clear_context_without_an_authentication_response() {
        val request = MockHttpServletRequest("GET", "/api/v1/admin/users/stats/total-users")
        request.addHeader("Authorization", "Bearer " + F3TestInputs.token(7, "ROLE_ADMIN"))
        val response = MockHttpServletResponse()
        val failure = IllegalStateException("synthetic-private-query")

        assertThatThrownBy {
            filter.doFilter(
                request,
                response,
                FilterChain { _, _ ->
                    assertThat(SecurityContextHolder.getContext().authentication!!.principal).isEqualTo(7L)
                    throw failure
                },
            )
        }.isSameAs(failure)

        assertThat(response.status).isEqualTo(200)
        assertThat(response.contentAsString).isEmpty()
        assertThat(SecurityContextHolder.getContext().authentication).isNull()
    }

    @Test
    fun givenVerifiedStreamExpiry__whenFiltering__thenSaveOnlyRequestContextWithLongIdentityAndInstantWithoutSessionOrCredentials() {
        val repository = spy(RequestAttributeSecurityContextRepository())
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
        verify(repository).saveContext(
            anyNonNull<SecurityContext>(),
            sameValue<HttpServletRequest>(request),
            sameValue<HttpServletResponse>(response),
        )
        assertThat(request.getSession(false)).isNull()
        assertThat(
            repository.loadDeferredContext(MockHttpServletRequest("GET", "/api/v1/notes/stream")).get().authentication,
        ).isNull()
        assertThat(SecurityContextHolder.getContext().authentication).isNull()
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun givenSignedTokenWithoutExpiry__whenFilteringRestOrExactStream__thenRequireExpiryOnlyForStreamAndNeverSaveRest(
        stream: Boolean,
    ) {
        val repository = spy(RequestAttributeSecurityContextRepository())
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
        filter.doFilter(
            request,
            response,
            FilterChain { _, _ ->
                reached.set(true)
                assertThat(SecurityContextHolder.getContext().authentication!!.details).isNull()
            },
        )
        assertThat(reached.get()).isEqualTo(!stream)
        assertThat(response.status).isEqualTo(if (stream) 401 else 200)
        verify(repository, never()).saveContext(
            anyNonNull<SecurityContext>(),
            anyNonNull<HttpServletRequest>(),
            anyNonNull<HttpServletResponse>(),
        )
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
