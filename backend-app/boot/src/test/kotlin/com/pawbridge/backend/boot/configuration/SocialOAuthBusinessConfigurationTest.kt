package com.pawbridge.backend.boot.configuration

import com.pawbridge.backend.user.internal.oauth.application.SocialLogin
import com.pawbridge.backend.user.internal.session.infrastructure.JdbcAuthSessionStore
import com.pawbridge.backend.user.internal.session.infrastructure.JwtSessionTokens
import io.mockk.justRun
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.support.JdbcTransactionManager
import java.util.function.Supplier

class SocialOAuthBusinessConfigurationTest {
    @ParameterizedTest
    @ValueSource(strings = ["absent", "false"])
    fun `구글이 꺼져 있거나 지정되지 않았을 때__비즈니스를 조립하면__로그인과 작성자 의존성을 만들지 않는다`(
        flag: String,
    ) {
        // given
        var runner = ApplicationContextRunner().withUserConfiguration(SocialOAuthBusinessConfiguration::class.java)
        if (flag == "false") {
            runner = runner.withPropertyValues("pawbridge.auth.google-enabled=false")
        }

        // when
        runner.run { context ->
            // then
            assertThat(context).hasNotFailed().doesNotHaveBean(SocialLogin::class.java)
        }
    }

    @Test
    fun `인증 세션 지원 없이 구글이 켜졌을 때__비즈니스를 조립하면__닫힌 채로 실패한다`() {
        // given
        val jdbc = mockk<JdbcTemplate>()
        val manager = mockk<JdbcTransactionManager>()
        val tokens = mockk<JwtSessionTokens>()
        val runner = ApplicationContextRunner().withUserConfiguration(SocialOAuthBusinessConfiguration::class.java)
            .withPropertyValues("pawbridge.auth.google-enabled=true")
            .withBean("writeJdbc", JdbcTemplate::class.java, Supplier { jdbc })
            .withBean("writeTransactionManager", JdbcTransactionManager::class.java, Supplier { manager })
            .withBean(JwtSessionTokens::class.java, Supplier { tokens })

        justRun { jdbc.afterPropertiesSet() }
        justRun { manager.afterPropertiesSet() }

        // when
        runner.run { context ->
            // then
            assertThat(context).hasFailed()
            assertThat(context.startupFailure).hasMessageContaining(JdbcAuthSessionStore::class.java.name)
        }
    }
}
