package com.pawbridge.backend.boot.configuration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.pawbridge.backend.user.internal.adminupdate.application.AdminUserUpdate;
import com.pawbridge.backend.user.internal.session.application.UserSessionRevoker;
import com.pawbridge.backend.user.internal.session.infrastructure.JdbcAuthSessionStore;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.test.util.ReflectionTestUtils;

class AdminUserUpdateConfigurationTest {

    private ApplicationContextRunner runner() {
        return new ApplicationContextRunner()
                .withUserConfiguration(AdminUserUpdateConfiguration.class)
                .withBean("writeJdbc", JdbcTemplate.class, () -> mock(JdbcTemplate.class))
                .withBean("writeTransactionManager", JdbcTransactionManager.class, () -> mock(JdbcTransactionManager.class));
    }

    @Test
    void given__auth_sessions_enabled__when__assembling__then__demotions_revoke_through_the_session_store() {
        var store = mock(JdbcAuthSessionStore.class);
        runner().withBean(JdbcAuthSessionStore.class, () -> store).run(context -> {
            assertThat(context).hasNotFailed();
            var update = context.getBean(AdminUserUpdate.class);
            assertThat(ReflectionTestUtils.getField(update, "sessions")).isSameAs(store);
        });
    }

    @Test
    void given__auth_sessions_disabled__when__assembling__then__there_is_nothing_to_revoke() {
        runner().run(context -> {
            assertThat(context).hasNotFailed();
            var update = context.getBean(AdminUserUpdate.class);
            assertThat(ReflectionTestUtils.getField(update, "sessions")).isSameAs(UserSessionRevoker.NONE);
        });
    }
}
