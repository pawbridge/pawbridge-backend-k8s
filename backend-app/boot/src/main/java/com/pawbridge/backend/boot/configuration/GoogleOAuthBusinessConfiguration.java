package com.pawbridge.backend.boot.configuration;

import com.pawbridge.backend.user.internal.oauth.application.GoogleLogin;
import com.pawbridge.backend.user.internal.oauth.infrastructure.JdbcGoogleLogin;
import com.pawbridge.backend.user.internal.session.application.AuthSessions;
import com.pawbridge.backend.user.internal.session.infrastructure.JwtSessionTokens;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Configuration(proxyBeanMethods=false)
@ConditionalOnProperty(name="pawbridge.auth.google-enabled",havingValue="true")
public class GoogleOAuthBusinessConfiguration {
    @Bean GoogleLogin googleLogin(@Qualifier("writeJdbc") JdbcTemplate jdbc,
            @Qualifier("writeTransactionManager") JdbcTransactionManager manager,JwtSessionTokens tokens,AuthSessions sessions) {
        var transaction=new TransactionTemplate(manager);
        return new JdbcGoogleLogin(jdbc,transaction,tokens);
    }
}
