package com.pawbridge.backend;

import com.pawbridge.backend.boot.configuration.LocalLoginConfiguration;
import com.pawbridge.backend.user.internal.login.application.LocalLogin;
import com.pawbridge.backend.user.internal.login.web.LocalLoginFilter;
import com.pawbridge.backend.user.internal.session.application.AuthSessions;
import com.pawbridge.backend.user.internal.session.infrastructure.JwtSessionTokens;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.DefaultSecurityFilterChain;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class LocalLoginConfigurationTest {
    @ParameterizedTest @ValueSource(strings={"absent","false"})
    void given__local_login_off__when__assembling_without_inputs__then__register_no_login_or_chain(String flag){
        var runner=new ApplicationContextRunner().withUserConfiguration(LocalLoginConfiguration.class);
        if(flag.equals("false"))runner=runner.withPropertyValues("pawbridge.auth.local-enabled=false");
        runner.run(context->assertThat(context).hasNotFailed().doesNotHaveBean(LocalLogin.class).doesNotHaveBean(SecurityFilterChain.class));
    }
    @ParameterizedTest @ValueSource(strings={"jdbc","manager","tokens","sessions"})
    void given__enabled_without_a_required_shared_input__when__assembling__then__fail_instead_of_creating_a_replacement(String missing){
        inputs(missing).run(context->assertThat(context).hasFailed());
    }
    @Test void given__local_login_enabled_without_explicit_session_flag__when__assembling__then__fail_before_authentication(){
        inputs("").withPropertyValues("pawbridge.auth.sessions-enabled=false").run(context->{
            assertThat(context).hasFailed();assertThat(context.getStartupFailure()).hasStackTraceContaining("Local login requires explicitly enabled auth sessions");
        });
    }
    @Test void given__enabled_shared_inputs__when__assembling__then__reuse_writer_and_issuer_with_one_exact_path_filter_and_no_global_authentication_beans(){
        inputs("").run(context->{
            assertThat(context).hasNotFailed();var login=context.getBean(LocalLogin.class);
            assertThat(ReflectionTestUtils.getField(login,"jdbc")).isSameAs(context.getBean("writeJdbc"));
            assertThat(ReflectionTestUtils.getField(login,"tokens")).isSameAs(context.getBean(JwtSessionTokens.class));
            var saves=(TransactionTemplate)ReflectionTestUtils.getField(login,"saves");
            assertThat(saves.getTransactionManager()).isSameAs(context.getBean("writeTransactionManager"));
            var chain=(DefaultSecurityFilterChain)context.getBean(SecurityFilterChain.class);
            for(String method:new String[]{"POST","GET","PATCH"}){
                var request=new MockHttpServletRequest(method,LocalLoginFilter.PATH);request.setServletPath(LocalLoginFilter.PATH);assertThat(chain.matches(request)).isTrue();
            }
            var other=new MockHttpServletRequest("POST",LocalLoginFilter.PATH+"/extra");other.setServletPath(LocalLoginFilter.PATH+"/extra");assertThat(chain.matches(other)).isFalse();
            assertThat(chain.getFilters().stream().filter(LocalLoginFilter.class::isInstance)).hasSize(1);
            assertThat(context).doesNotHaveBean(LocalLoginFilter.class).doesNotHaveBean(AuthenticationManager.class)
                    .doesNotHaveBean(AuthenticationProvider.class).doesNotHaveBean(PasswordEncoder.class).doesNotHaveBean(UserDetailsService.class);
        });
    }
    private WebApplicationContextRunner inputs(String missing){
        var runner=new WebApplicationContextRunner().withUserConfiguration(LocalLoginConfiguration.class,SecurityInputs.class)
                .withPropertyValues("pawbridge.auth.local-enabled=true","pawbridge.auth.sessions-enabled=true");
        if(!missing.equals("jdbc"))runner=runner.withBean("writeJdbc",JdbcTemplate.class,()->mock(JdbcTemplate.class));
        if(!missing.equals("manager"))runner=runner.withBean("writeTransactionManager",JdbcTransactionManager.class,()->mock(JdbcTransactionManager.class));
        if(!missing.equals("tokens"))runner=runner.withBean(JwtSessionTokens.class,()->mock(JwtSessionTokens.class));
        if(!missing.equals("sessions"))runner=runner.withBean(AuthSessions.class,()->mock(AuthSessions.class));
        return runner;
    }
    @Configuration(proxyBeanMethods=false) @EnableWebSecurity static class SecurityInputs {}
}
