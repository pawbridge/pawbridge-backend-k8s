package com.pawbridge.backend.boot.configuration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.pawbridge.backend.user.internal.oauth.application.SocialLogin;
import com.pawbridge.backend.user.internal.session.infrastructure.JdbcAuthSessionStore;
import com.pawbridge.backend.user.internal.session.infrastructure.JwtSessionTokens;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.JdbcTransactionManager;

class SocialOAuthBusinessConfigurationTest {
    @ParameterizedTest @ValueSource(strings={"absent","false"})
    void given__google_disabled_or_unspecified__when__assembling_business__then__create_no_login_or_writer_dependency(String flag){
        var runner=new ApplicationContextRunner().withUserConfiguration(SocialOAuthBusinessConfiguration.class);
        if(flag.equals("false"))runner=runner.withPropertyValues("pawbridge.auth.google-enabled=false");
        runner.run(context->assertThat(context).hasNotFailed().doesNotHaveBean(SocialLogin.class));
    }
    @Test
    void given__google_enabled_without_auth_sessions_support__when__assembling_business__then__fail_closed(){
        new ApplicationContextRunner().withUserConfiguration(SocialOAuthBusinessConfiguration.class)
                .withPropertyValues("pawbridge.auth.google-enabled=true")
                .withBean("writeJdbc",JdbcTemplate.class,()->mock(JdbcTemplate.class))
                .withBean("writeTransactionManager",JdbcTransactionManager.class,()->mock(JdbcTransactionManager.class))
                .withBean(JwtSessionTokens.class,()->mock(JwtSessionTokens.class))
                .run(context->{assertThat(context).hasFailed();assertThat(context.getStartupFailure()).hasMessageContaining(JdbcAuthSessionStore.class.getName());});
    }
}
