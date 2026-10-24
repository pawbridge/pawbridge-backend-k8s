package com.pawbridge.backend.boot.configuration;

import com.pawbridge.backend.boot.security.GatewayCors;
import com.pawbridge.backend.user.internal.login.application.LocalLogin;
import com.pawbridge.backend.user.internal.login.infrastructure.JdbcLocalLogin;
import com.pawbridge.backend.user.internal.login.web.LocalLoginFilter;
import com.pawbridge.backend.user.internal.login.web.LocalLoginPrincipal;
import com.pawbridge.backend.user.internal.session.infrastructure.JdbcAuthSessionStore;
import com.pawbridge.backend.user.internal.session.infrastructure.JwtSessionTokens;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.context.RequestAttributeSecurityContextRepository;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.filter.CorsFilter;

@Configuration(proxyBeanMethods=false)
@ConditionalOnProperty(prefix="pawbridge.auth",name="local-enabled",havingValue="true",matchIfMissing=false)
public class LocalLoginConfiguration {
    @Bean
    LocalLogin localLogin(@Qualifier("writeJdbc") JdbcTemplate jdbc,
            @Qualifier("writeTransactionManager") JdbcTransactionManager manager, JwtSessionTokens tokens,
            JdbcAuthSessionStore sessions, @Value("${pawbridge.auth.sessions-enabled:false}") boolean sessionsEnabled) {
        if (!sessionsEnabled) {
            throw new IllegalArgumentException("Local login requires explicitly enabled auth sessions");
        }
        return new JdbcLocalLogin(jdbc, new TransactionTemplate(manager), tokens, sessions);
    }
    @Bean
    @Order(0)
    SecurityFilterChain localLoginSecurity(
            HttpSecurity http,
            LocalLogin login,
            @Value(GatewayCors.ORIGIN_PATTERNS_VALUE) String corsOriginPatterns) throws Exception {
        UserDetailsService users = email -> login.findByEmail(email)
                .map(LocalLoginPrincipal::new)
                .orElseThrow(() -> new UsernameNotFoundException("사용자를 찾을 수 없습니다: " + email));

        var provider = new DaoAuthenticationProvider();
        provider.setUserDetailsService(users);
        provider.setPasswordEncoder(new BCryptPasswordEncoder());
        var manager = new ProviderManager(provider);

        var securityContextRepository = new RequestAttributeSecurityContextRepository();
        var localLoginFilter = new LocalLoginFilter(manager, login);

        return http
                .securityMatcher(new AntPathRequestMatcher(LocalLoginFilter.PATH))
                .csrf(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .requestCache(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .securityContext(context -> context
                        .securityContextRepository(securityContextRepository)
                        .requireExplicitSave(true))
                .authenticationManager(manager)
                .authorizeHttpRequests(requests -> requests.anyRequest().permitAll())
                // The browser sends a preflight OPTIONS before the cross-origin login POST. CorsFilter sits before
                // LocalLoginFilter, so the preflight is answered here and never reaches authentication.
                .addFilterAt(GatewayCors.filter(corsOriginPatterns), CorsFilter.class)
                .addFilterBefore(localLoginFilter, UsernamePasswordAuthenticationFilter.class)
                .build();
    }
}
