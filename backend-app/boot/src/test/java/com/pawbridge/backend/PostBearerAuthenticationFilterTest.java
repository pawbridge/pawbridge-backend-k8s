package com.pawbridge.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.pawbridge.backend.boot.security.PostBearerAuthenticationFilter;
import com.pawbridge.backend.boot.security.PostTokenAuthenticator;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

class PostBearerAuthenticationFilterTest {
    private final PostBearerAuthenticationFilter filter = new PostBearerAuthenticationFilter(
            new PostTokenAuthenticator(F3TestInputs.KEY), request -> true);

    @ParameterizedTest
    @MethodSource("verifiedRoles")
    void given__verified_role__when__filtering_a_protected_request__then__keep_long_principal_null_credentials_and_only_exact_admin_authority(
            String role, List<String> authorities) throws Exception {
        var request = new MockHttpServletRequest("GET", "/api/v1/admin/users/stats/total-users");
        request.addHeader("Authorization", "Bearer " + F3TestInputs.token(7, role));

        filter.doFilter(request, new MockHttpServletResponse(), (filteredRequest, response) -> {
            var identity = SecurityContextHolder.getContext().getAuthentication();
            assertThat(identity.isAuthenticated()).isTrue();
            assertThat(identity.getPrincipal()).isInstanceOf(Long.class).isEqualTo(7L);
            assertThat(identity.getCredentials()).isNull();
            assertThat(identity.getDetails()).isInstanceOf(java.time.Instant.class);
            assertThat(identity.getAuthorities()).extracting(GrantedAuthority::getAuthority)
                    .containsExactlyElementsOf(authorities);
        });

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    static Stream<Arguments> verifiedRoles() {
        return Stream.of(Arguments.of("ROLE_ADMIN", List.of("ROLE_ADMIN")),
                Arguments.of("ROLE_UNKNOWN", List.of()));
    }

    @Test
    void given__business_failure_after_authentication__when__the_chain_throws__then__propagate_it_and_clear_context_without_an_authentication_response() throws Exception {
        var request = new MockHttpServletRequest("GET", "/api/v1/admin/users/stats/total-users");
        request.addHeader("Authorization", "Bearer " + F3TestInputs.token(7, "ROLE_ADMIN"));
        var response = new MockHttpServletResponse();
        var failure = new IllegalStateException("synthetic-private-query");

        assertThatThrownBy(() -> filter.doFilter(request, response, (filteredRequest, filteredResponse) -> {
            assertThat(SecurityContextHolder.getContext().getAuthentication().getPrincipal()).isEqualTo(7L);
            throw failure;
        })).isSameAs(failure);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getContentAsString()).isEmpty();
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void givenVerifiedStreamExpiry__whenFiltering__thenSaveOnlyRequestContextWithLongIdentityAndInstantWithoutSessionOrCredentials() throws Exception {
        var repository = org.mockito.Mockito.spy(new org.springframework.security.web.context.RequestAttributeSecurityContextRepository());
        var filter = new PostBearerAuthenticationFilter(new PostTokenAuthenticator(F3TestInputs.KEY), request -> true,
                request -> request.getRequestURI().equals("/api/v1/notes/stream"), repository);
        var expiry = java.time.Instant.now().plusSeconds(120).truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        String token = io.jsonwebtoken.Jwts.builder().claim("userId", 7).claim("role", "ROLE_USER")
                .expiration(java.util.Date.from(expiry)).signWith(F3TestInputs.KEY).compact();
        var request = new MockHttpServletRequest("GET", "/api/v1/notes/stream");
        request.addHeader("Authorization", "Bearer " + token);
        request.addHeader("X-Auth-Expires-At", Long.MAX_VALUE);
        var response = new MockHttpServletResponse();

        filter.doFilter(request, response, (ignored, output) -> {
            var identity = SecurityContextHolder.getContext().getAuthentication();
            assertThat(identity.getPrincipal()).isEqualTo(7L); assertThat(identity.getDetails()).isEqualTo(expiry);
            assertThat(identity.getCredentials()).isNull();
            assertThat(repository.loadDeferredContext(request).get().getAuthentication()).isSameAs(identity);
        });
        org.mockito.Mockito.verify(repository).saveContext(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.same(request), org.mockito.ArgumentMatchers.same(response));
        assertThat(request.getSession(false)).isNull();
        assertThat(repository.loadDeferredContext(new MockHttpServletRequest("GET", "/api/v1/notes/stream")).get().getAuthentication()).isNull();
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void givenSignedTokenWithoutExpiry__whenFilteringRestOrExactStream__thenRequireExpiryOnlyForStreamAndNeverSaveRest(boolean stream) throws Exception {
        var repository = org.mockito.Mockito.spy(new org.springframework.security.web.context.RequestAttributeSecurityContextRepository());
        var filter = new PostBearerAuthenticationFilter(new PostTokenAuthenticator(F3TestInputs.KEY), request -> true,
                request -> request.getRequestURI().equals("/api/v1/notes/stream"), repository);
        String token = io.jsonwebtoken.Jwts.builder().claim("userId", 7).claim("role", "ROLE_UNKNOWN").signWith(F3TestInputs.KEY).compact();
        var request = new MockHttpServletRequest("GET", stream ? "/api/v1/notes/stream" : "/api/v1/notes");
        request.addHeader("Authorization", "Bearer " + token);
        var response = new MockHttpServletResponse(); var reached = new java.util.concurrent.atomic.AtomicBoolean();
        filter.doFilter(request, response, (ignored, output) -> {
            reached.set(true); assertThat(SecurityContextHolder.getContext().getAuthentication().getDetails()).isNull();
        });
        assertThat(reached.get()).isEqualTo(!stream); assertThat(response.getStatus()).isEqualTo(stream ? 401 : 200);
        org.mockito.Mockito.verify(repository, org.mockito.Mockito.never()).saveContext(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        assertThat(request.getSession(false)).isNull();
    }
}
