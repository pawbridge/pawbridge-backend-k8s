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
            assertThat(identity.getDetails()).isNull();
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
}
