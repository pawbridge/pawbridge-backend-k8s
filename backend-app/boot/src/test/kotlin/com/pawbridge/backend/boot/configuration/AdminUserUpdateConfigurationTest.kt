package com.pawbridge.backend.boot.configuration

import com.pawbridge.backend.user.internal.adminupdate.application.AdminUserUpdate
import com.pawbridge.backend.user.internal.session.application.UserSessionRevoker
import com.pawbridge.backend.user.internal.session.infrastructure.JdbcAuthSessionStore
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.support.JdbcTransactionManager
import org.springframework.test.util.ReflectionTestUtils
import java.util.function.Supplier

class AdminUserUpdateConfigurationTest {

    private fun runner(): ApplicationContextRunner {
        return ApplicationContextRunner()
            .withUserConfiguration(AdminUserUpdateConfiguration::class.java)
            .withBean("writeJdbc", JdbcTemplate::class.java, Supplier { mock(JdbcTemplate::class.java) })
            .withBean(
                "writeTransactionManager",
                JdbcTransactionManager::class.java,
                Supplier { mock(JdbcTransactionManager::class.java) },
            )
    }

    @Test
    fun given__auth_sessions_enabled__when__assembling__then__demotions_revoke_through_the_session_store() {
        val store = mock(JdbcAuthSessionStore::class.java)
        runner().withBean(JdbcAuthSessionStore::class.java, Supplier { store }).run { context ->
            assertThat(context).hasNotFailed()
            val update = context.getBean(AdminUserUpdate::class.java)
            assertThat(ReflectionTestUtils.getField(update, "sessions")).isSameAs(store)
        }
    }

    @Test
    fun given__auth_sessions_disabled__when__assembling__then__there_is_nothing_to_revoke() {
        runner().run { context ->
            assertThat(context).hasNotFailed()
            val update = context.getBean(AdminUserUpdate::class.java)
            assertThat(ReflectionTestUtils.getField(update, "sessions")).isSameAs(UserSessionRevoker.NONE)
        }
    }
}
