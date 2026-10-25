package com.pawbridge.backend.boot.configuration

import com.pawbridge.backend.user.internal.oauth.application.SocialLogin
import com.pawbridge.backend.user.internal.session.infrastructure.JdbcAuthSessionStore
import com.pawbridge.backend.user.internal.session.infrastructure.JwtSessionTokens
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.mockito.Mockito.mock
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.support.JdbcTransactionManager
import java.util.function.Supplier

class SocialOAuthBusinessConfigurationTest {
    @ParameterizedTest
    @ValueSource(strings = ["absent", "false"])
    fun given__google_disabled_or_unspecified__when__assembling_business__then__create_no_login_or_writer_dependency(
        flag: String,
    ) {
        var runner = ApplicationContextRunner().withUserConfiguration(SocialOAuthBusinessConfiguration::class.java)
        if (flag == "false") {
            runner = runner.withPropertyValues("pawbridge.auth.google-enabled=false")
        }
        runner.run { context -> assertThat(context).hasNotFailed().doesNotHaveBean(SocialLogin::class.java) }
    }

    @Test
    fun given__google_enabled_without_auth_sessions_support__when__assembling_business__then__fail_closed() {
        ApplicationContextRunner().withUserConfiguration(SocialOAuthBusinessConfiguration::class.java)
            .withPropertyValues("pawbridge.auth.google-enabled=true")
            .withBean("writeJdbc", JdbcTemplate::class.java, Supplier { mock(JdbcTemplate::class.java) })
            .withBean(
                "writeTransactionManager",
                JdbcTransactionManager::class.java,
                Supplier { mock(JdbcTransactionManager::class.java) },
            )
            .withBean(JwtSessionTokens::class.java, Supplier { mock(JwtSessionTokens::class.java) })
            .run { context ->
                assertThat(context).hasFailed()
                assertThat(context.startupFailure).hasMessageContaining(JdbcAuthSessionStore::class.java.name)
            }
    }
}
