package com.pawbridge.backend.boot.configuration;

import com.pawbridge.backend.boot.security.GatewayCors;
import com.pawbridge.backend.user.internal.session.application.AuthSessions;
import com.pawbridge.backend.user.internal.session.infrastructure.AuthSessionPolicy;
import com.pawbridge.backend.user.internal.session.infrastructure.JdbcAuthSessionStore;
import com.pawbridge.backend.user.internal.session.infrastructure.JdbcAuthSessions;
import com.pawbridge.backend.user.internal.session.infrastructure.JwtSessionTokens;
import com.pawbridge.backend.user.internal.session.infrastructure.RefreshTokens;
import com.pawbridge.backend.user.internal.session.web.AuthSessionController;
import com.pawbridge.backend.user.internal.session.web.AuthSessionExceptionHandler;
import java.util.Arrays;
import java.util.List;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Configuration(proxyBeanMethods=false)
@ConditionalOnProperty(prefix="pawbridge.auth",name="sessions-enabled",havingValue="true",matchIfMissing=false)
public class AuthSessionConfiguration {
    @Bean
    JwtSessionTokens sessionTokens(PostWriteSettings writer,
            @Value("${jwt.access-token-expiration}") long accessExpiration) {
        return new JwtSessionTokens(writer.jwtKey(), accessExpiration);
    }

    @Bean
    AuthSessionPolicy authSessionPolicy(
            @Value("${jwt.refresh-token-expiration}") long idleLifetimeMillis,
            @Value("${pawbridge.auth.session-absolute-days:30}") int absoluteLifetimeDays) {
        return AuthSessionPolicy.of(idleLifetimeMillis, absoluteLifetimeDays);
    }

    @Bean
    RefreshTokens refreshTokens() {
        return new RefreshTokens();
    }

    @Bean
    JdbcAuthSessionStore authSessionStore(@Qualifier("writeJdbc") JdbcTemplate jdbc, AuthSessionPolicy policy,
            RefreshTokens refreshTokens) {
        return new JdbcAuthSessionStore(jdbc, policy, refreshTokens);
    }

    @Bean
    AuthSessions authSessions(@Qualifier("writeJdbc") JdbcTemplate jdbc,
            @Qualifier("writeTransactionManager") JdbcTransactionManager manager, JwtSessionTokens tokens,
            JdbcAuthSessionStore store, RefreshTokens refreshTokens) {
        var transaction = new TransactionTemplate(manager);
        transaction.setTimeout(15);
        return new JdbcAuthSessions(jdbc, transaction, tokens, store, refreshTokens);
    }

    @Bean
    AuthSessionController authSessionController(AuthSessions sessions,
            @Value(GatewayCors.ORIGIN_PATTERNS_VALUE) String corsOriginPatterns) {
        return new AuthSessionController(sessions, exactOrigins(corsOriginPatterns));
    }

    @Bean
    AuthSessionExceptionHandler authSessionExceptionHandler() {
        return new AuthSessionExceptionHandler();
    }

    /** Wildcard CORS patterns are left out on purpose: cookie endpoints only trust explicitly listed origins. */
    static List<String> exactOrigins(String corsOriginPatterns) {
        return Arrays.stream(corsOriginPatterns.split(","))
                .map(String::trim)
                .filter(origin -> !origin.isEmpty())
                .filter(origin -> !origin.contains("*"))
                .toList();
    }
}
