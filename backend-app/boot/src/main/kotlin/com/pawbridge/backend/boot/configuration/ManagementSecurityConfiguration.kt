package com.pawbridge.backend.boot.configuration

import com.pawbridge.backend.boot.security.ManagementServerRequestMatcher
import jakarta.servlet.DispatcherType
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.access.AccessDeniedHandler
import org.springframework.security.web.authentication.HttpStatusEntryPoint
import org.springframework.security.web.util.matcher.AntPathRequestMatcher

/**
 * Security for the internal management port only. The chain runs before the login, OAuth and main chains, so
 * nothing but the scrape and health paths is reachable there, whatever those chains would otherwise permit.
 * Chains for the main port are not changed and never match a management-port request.
 */
@Configuration(proxyBeanMethods = false)
class ManagementSecurityConfiguration {
    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE)
    @Throws(Exception::class)
    fun managementSecurity(http: HttpSecurity): SecurityFilterChain {
        val prometheus = AntPathRequestMatcher("/actuator/prometheus", HttpMethod.GET.name())
        val health = AntPathRequestMatcher("/actuator/health", HttpMethod.GET.name())
        val healthGroups = AntPathRequestMatcher("/actuator/health/**", HttpMethod.GET.name())
        return http
            .securityMatcher(ManagementServerRequestMatcher())
            .csrf { it.disable() }
            .httpBasic { it.disable() }
            .formLogin { it.disable() }
            .logout { it.disable() }
            .requestCache { it.disable() }
            .sessionManagement { session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            .authorizeHttpRequests { requests ->
                requests
                    .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                    .requestMatchers(prometheus, health, healthGroups).permitAll()
                    .anyRequest().denyAll()
            }
            .exceptionHandling { errors ->
                errors
                    .authenticationEntryPoint(HttpStatusEntryPoint(HttpStatus.FORBIDDEN))
                    .accessDeniedHandler(
                        AccessDeniedHandler { _, response, _ -> response.status = HttpStatus.FORBIDDEN.value() },
                    )
            }
            .build()
    }
}
