package com.pawbridge.backend.boot.configuration

import com.pawbridge.backend.user.internal.oauth.application.SocialLogin
import com.pawbridge.backend.user.internal.oauth.infrastructure.JdbcSocialLogin
import com.pawbridge.backend.user.internal.session.infrastructure.JdbcAuthSessionStore
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.support.JdbcTransactionManager
import org.springframework.transaction.support.TransactionTemplate

@Configuration(proxyBeanMethods = false)
@ConditionalOnExpression("\${pawbridge.auth.google-enabled:false} or \${pawbridge.auth.naver-enabled:false}")
class SocialOAuthBusinessConfiguration {
    @Bean
    fun googleLogin(
        @Qualifier("writeJdbc") jdbc: JdbcTemplate,
        @Qualifier("writeTransactionManager") manager: JdbcTransactionManager,
        sessions: JdbcAuthSessionStore,
    ): SocialLogin {
        val transaction = TransactionTemplate(manager)
        return JdbcSocialLogin(jdbc, transaction, sessions)
    }
}
