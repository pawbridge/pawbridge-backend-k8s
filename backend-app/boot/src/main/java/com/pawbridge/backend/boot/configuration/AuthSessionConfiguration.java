package com.pawbridge.backend.boot.configuration;

import com.pawbridge.backend.user.internal.session.application.AuthSessions;
import com.pawbridge.backend.user.internal.session.infrastructure.JdbcAuthSessions;
import com.pawbridge.backend.user.internal.session.infrastructure.JwtSessionTokens;
import com.pawbridge.backend.user.internal.session.web.AuthSessionController;
import com.pawbridge.backend.user.internal.session.web.AuthSessionExceptionHandler;
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
            @Value("${jwt.access-token-expiration}") long accessExpiration,
            @Value("${jwt.refresh-token-expiration}") long refreshExpiration) {
        return new JwtSessionTokens(writer.jwtKey(),accessExpiration,refreshExpiration);
    }
    @Bean
    AuthSessions authSessions(@Qualifier("writeJdbc") JdbcTemplate jdbc,
            @Qualifier("writeTransactionManager") JdbcTransactionManager manager,JwtSessionTokens tokens) {
        var transaction=new TransactionTemplate(manager);
        transaction.setTimeout(15);
        return new JdbcAuthSessions(jdbc,transaction,tokens);
    }
    @Bean AuthSessionController authSessionController(AuthSessions sessions) { return new AuthSessionController(sessions); }
    @Bean AuthSessionExceptionHandler authSessionExceptionHandler() { return new AuthSessionExceptionHandler(); }
}
