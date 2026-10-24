package com.pawbridge.backend;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.pawbridge.backend.boot.configuration.AuthSessionConfiguration;
import com.pawbridge.backend.user.internal.session.application.AuthSessions;
import com.pawbridge.backend.user.internal.session.infrastructure.AuthSessionPolicy;
import com.pawbridge.backend.user.internal.session.infrastructure.JdbcAuthSessionStore;
import com.pawbridge.backend.user.internal.session.infrastructure.JwtSessionTokens;
import com.pawbridge.backend.user.internal.session.web.AuthSessionController;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;

class AuthSessionConfigurationTest {
    @ParameterizedTest @ValueSource(strings={"absent","false"})
    void given__flag_off_without_any_ttl__when__assembling__then__no_session_beans_or_key_inputs_needed(String flag) {
        var runner=new ApplicationContextRunner().withUserConfiguration(AuthSessionConfiguration.class);
        if(!flag.equals("absent"))runner=runner.withPropertyValues("pawbridge.auth.sessions-enabled=false");
        runner.run(context->{assertThat(context).hasNotFailed();assertThat(context.getBeansOfType(AuthSessions.class)).isEmpty();assertThat(context.getBeansOfType(JwtSessionTokens.class)).isEmpty();});
    }
    @ParameterizedTest @ValueSource(strings={"jwt.access-token-expiration","jwt.refresh-token-expiration"})
    void given__enabled_without_one_original_ttl__when__assembling__then__fail_start_without_inventing_a_default(String missing) {
        var runner=inputs().withPropertyValues("pawbridge.auth.sessions-enabled=true");
        for(String property:List.of("jwt.access-token-expiration","jwt.refresh-token-expiration"))if(!property.equals(missing))runner=runner.withPropertyValues(property+"=600000");
        runner.run(context->{assertThat(context).hasFailed();assertThat(context.getStartupFailure()).hasStackTraceContaining(missing);});
    }
    @Test void given__enabled_with_original_ttls__when__assembling__then__reuse_exact_existing_key_writer_and_manager() {
        var settings=F3TestInputs.settings();
        inputs(settings).withPropertyValues("pawbridge.auth.sessions-enabled=true","jwt.access-token-expiration=600000","jwt.refresh-token-expiration=1234")
                .run(context->{assertThat(context).hasNotFailed();assertThat(context.getBeansOfType(AuthSessions.class)).hasSize(1);assertThat(context.getBeansOfType(JwtSessionTokens.class)).hasSize(1);
                    var tokens=context.getBean(JwtSessionTokens.class);assertThat(ReflectionTestUtils.getField(tokens,"key")).isSameAs(settings.jwtKey());assertThat(ReflectionTestUtils.getField(tokens,"accessExpiration")).isEqualTo(600000L);
                    var sessions=context.getBean(AuthSessions.class);assertThat(ReflectionTestUtils.getField(sessions,"jdbc")).isSameAs(context.getBean("writeJdbc"));
                    var tx=(TransactionTemplate)ReflectionTestUtils.getField(sessions,"transactions");assertThat(tx.getTransactionManager()).isSameAs(context.getBean("writeTransactionManager"));assertThat(tx.getTimeout()).isEqualTo(15);assertThat(tx.isReadOnly()).isFalse();
                    assertThat(context.getBeansOfType(javax.sql.DataSource.class)).isEmpty();verifyNoInteractions(context.getBean("writeJdbc"),context.getBean("writeTransactionManager"));});
    }

    @Test
    void given__enabled_with_defaults__when__assembling__then__use_the_refresh_ttl_as_idle_lifetime_and_thirty_absolute_days() {
        inputs().withPropertyValues("pawbridge.auth.sessions-enabled=true", "jwt.access-token-expiration=600000",
                        "jwt.refresh-token-expiration=1209600000")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    var policy = context.getBean(AuthSessionPolicy.class);
                    assertThat(policy.idleLifetime()).isEqualTo(Duration.ofDays(14));
                    assertThat(policy.absoluteLifetime()).isEqualTo(Duration.ofDays(30));
                    assertThat(policy.maxActiveSessions()).isEqualTo(5);
                    assertThat(policy.reuseGrace()).isEqualTo(Duration.ofSeconds(60));
                    var store = context.getBean(JdbcAuthSessionStore.class);
                    assertThat(ReflectionTestUtils.getField(store, "jdbc")).isSameAs(context.getBean("writeJdbc"));
                });
    }

    @Test
    void given__a_configured_absolute_lifetime__when__assembling__then__apply_it() {
        inputs().withPropertyValues("pawbridge.auth.sessions-enabled=true", "jwt.access-token-expiration=600000",
                        "jwt.refresh-token-expiration=1209600000", "pawbridge.auth.session-absolute-days=7")
                .run(context -> assertThat(context.getBean(AuthSessionPolicy.class).absoluteLifetime()).isEqualTo(Duration.ofDays(7)));
    }

    @Test
    void given__cors_patterns_with_wildcards__when__assembling_the_controller__then__trust_only_exact_origins() {
        inputs().withPropertyValues("pawbridge.auth.sessions-enabled=true", "jwt.access-token-expiration=600000",
                        "jwt.refresh-token-expiration=1209600000",
                        "pawbridge.cors.allowed-origin-patterns= https://www.pawbridge.kr ,https://*.pawbridge.kr,,https://pawbridge.kr")
                .run(context -> {
                    var controller = context.getBean(AuthSessionController.class);
                    assertThat((java.util.Set<Object>) ReflectionTestUtils.getField(controller, "allowedOrigins"))
                            .containsExactlyInAnyOrder("https://www.pawbridge.kr", "https://pawbridge.kr");
                });
    }

    private ApplicationContextRunner inputs(){return inputs(F3TestInputs.settings());}
    private ApplicationContextRunner inputs(com.pawbridge.backend.boot.configuration.PostWriteSettings settings){return new ApplicationContextRunner().withUserConfiguration(AuthSessionConfiguration.class)
            .withInitializer(context->{context.getBeanFactory().registerSingleton("postWriteSettings",settings);context.getBeanFactory().registerSingleton("writeJdbc",mock(JdbcTemplate.class));context.getBeanFactory().registerSingleton("writeTransactionManager",mock(JdbcTransactionManager.class));});}
}
