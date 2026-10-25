package com.pawbridge.backend.boot.configuration

import com.pawbridge.backend.user.internal.adminupdate.application.AdminUserUpdate
import com.pawbridge.backend.user.internal.session.application.UserSessionRevoker
import com.pawbridge.backend.user.internal.session.infrastructure.JdbcAuthSessionStore
import io.mockk.justRun
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.support.JdbcTransactionManager
import org.springframework.test.util.ReflectionTestUtils
import java.util.function.Supplier

class AdminUserUpdateConfigurationTest {

    private fun runner(): ApplicationContextRunner {
        val jdbc = mockk<JdbcTemplate>()
        val transactionManager = mockk<JdbcTransactionManager>()
        justRun { jdbc.afterPropertiesSet() }
        justRun { transactionManager.afterPropertiesSet() }
        return ApplicationContextRunner()
            .withUserConfiguration(AdminUserUpdateConfiguration::class.java)
            .withBean("writeJdbc", JdbcTemplate::class.java, Supplier { jdbc })
            .withBean("writeTransactionManager", JdbcTransactionManager::class.java, Supplier { transactionManager })
    }

    @Test
    fun `세션 저장소가 있을 때__조립하면__강등이 세션 저장소를 통해 세션을 끊는다`() {
        // given
        val store = mockk<JdbcAuthSessionStore>()

        // when
        runner().withBean(JdbcAuthSessionStore::class.java, Supplier { store }).run { context ->
            // then
            assertThat(context).hasNotFailed()
            val update = context.getBean(AdminUserUpdate::class.java)
            assertThat(ReflectionTestUtils.getField(update, "sessions")).isSameAs(store)
        }
    }

    @Test
    fun `세션 저장소가 없을 때__조립하면__끊을 세션이 없다`() {
        // given
        val runner = runner()

        // when
        runner.run { context ->
            // then
            assertThat(context).hasNotFailed()
            val update = context.getBean(AdminUserUpdate::class.java)
            assertThat(ReflectionTestUtils.getField(update, "sessions")).isSameAs(UserSessionRevoker.NONE)
        }
    }
}
