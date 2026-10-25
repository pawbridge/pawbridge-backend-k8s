package com.pawbridge.backend

import com.pawbridge.backend.boot.configuration.LocalLoginConfiguration
import com.pawbridge.backend.user.internal.login.application.LocalLogin
import com.pawbridge.backend.user.internal.login.web.LocalLoginFilter
import com.pawbridge.backend.user.internal.session.infrastructure.JdbcAuthSessionStore
import com.pawbridge.backend.user.internal.session.infrastructure.JwtSessionTokens
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.mockito.Mockito.mock
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.boot.test.context.runner.WebApplicationContextRunner
import org.springframework.context.annotation.Configuration
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.support.JdbcTransactionManager
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.security.authentication.AuthenticationManager
import org.springframework.security.authentication.AuthenticationProvider
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.core.userdetails.UserDetailsService
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.web.DefaultSecurityFilterChain
import org.springframework.security.web.SecurityFilterChain
import org.springframework.test.util.ReflectionTestUtils
import org.springframework.transaction.support.TransactionTemplate
import java.util.function.Supplier

class LocalLoginConfigurationTest {
    @ParameterizedTest
    @ValueSource(strings = ["absent", "false"])
    fun given__local_login_off__when__assembling_without_inputs__then__register_no_login_or_chain(flag: String) {
        var runner = ApplicationContextRunner().withUserConfiguration(LocalLoginConfiguration::class.java)
        if (flag == "false") {
            runner = runner.withPropertyValues("pawbridge.auth.local-enabled=false")
        }
        runner.run { context ->
            assertThat(context).hasNotFailed()
                .doesNotHaveBean(LocalLogin::class.java)
                .doesNotHaveBean(SecurityFilterChain::class.java)
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["jdbc", "manager", "tokens", "sessions"])
    fun given__enabled_without_a_required_shared_input__when__assembling__then__fail_instead_of_creating_a_replacement(
        missing: String,
    ) {
        inputs(missing).run { context -> assertThat(context).hasFailed() }
    }

    @Test
    fun given__local_login_enabled_without_explicit_session_flag__when__assembling__then__fail_before_authentication() {
        inputs("").withPropertyValues("pawbridge.auth.sessions-enabled=false").run { context ->
            assertThat(context).hasFailed()
            assertThat(context.startupFailure).hasStackTraceContaining("Local login requires explicitly enabled auth sessions")
        }
    }

    @Test
    fun given__enabled_shared_inputs__when__assembling__then__reuse_writer_and_issuer_with_one_exact_path_filter_and_no_global_authentication_beans() {
        inputs("").run { context ->
            assertThat(context).hasNotFailed()
            val login = context.getBean(LocalLogin::class.java)
            assertThat(ReflectionTestUtils.getField(login, "jdbc")).isSameAs(context.getBean("writeJdbc"))
            assertThat(ReflectionTestUtils.getField(login, "tokens")).isSameAs(context.getBean(JwtSessionTokens::class.java))
            assertThat(ReflectionTestUtils.getField(login, "sessions"))
                .isSameAs(context.getBean(JdbcAuthSessionStore::class.java))
            val saves = ReflectionTestUtils.getField(login, "saves") as TransactionTemplate
            assertThat(saves.transactionManager).isSameAs(context.getBean("writeTransactionManager"))
            val chain = context.getBean(SecurityFilterChain::class.java) as DefaultSecurityFilterChain
            for (method in arrayOf("POST", "GET", "PATCH")) {
                val request = MockHttpServletRequest(method, LocalLoginFilter.PATH)
                request.servletPath = LocalLoginFilter.PATH
                assertThat(chain.matches(request)).isTrue()
            }
            val other = MockHttpServletRequest("POST", LocalLoginFilter.PATH + "/extra")
            other.servletPath = LocalLoginFilter.PATH + "/extra"
            assertThat(chain.matches(other)).isFalse()
            assertThat(chain.filters.filterIsInstance<LocalLoginFilter>()).hasSize(1)
            assertThat(context)
                .doesNotHaveBean(LocalLoginFilter::class.java)
                .doesNotHaveBean(AuthenticationManager::class.java)
                .doesNotHaveBean(AuthenticationProvider::class.java)
                .doesNotHaveBean(PasswordEncoder::class.java)
                .doesNotHaveBean(UserDetailsService::class.java)
        }
    }

    private fun inputs(missing: String): WebApplicationContextRunner {
        var runner = WebApplicationContextRunner()
            .withUserConfiguration(LocalLoginConfiguration::class.java, SecurityInputs::class.java)
            .withPropertyValues("pawbridge.auth.local-enabled=true", "pawbridge.auth.sessions-enabled=true")
        if (missing != "jdbc") {
            runner = runner.withBean("writeJdbc", JdbcTemplate::class.java, Supplier { mock(JdbcTemplate::class.java) })
        }
        if (missing != "manager") {
            runner = runner.withBean(
                "writeTransactionManager",
                JdbcTransactionManager::class.java,
                Supplier { mock(JdbcTransactionManager::class.java) },
            )
        }
        if (missing != "tokens") {
            runner = runner.withBean(JwtSessionTokens::class.java, Supplier { mock(JwtSessionTokens::class.java) })
        }
        if (missing != "sessions") {
            runner = runner.withBean(JdbcAuthSessionStore::class.java, Supplier { mock(JdbcAuthSessionStore::class.java) })
        }
        return runner
    }

    @Configuration(proxyBeanMethods = false)
    @EnableWebSecurity
    class SecurityInputs
}
