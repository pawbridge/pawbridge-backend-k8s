package com.pawbridge.backend.boot.configuration

import org.springframework.context.annotation.Configuration
import org.springframework.data.jpa.repository.config.EnableJpaAuditing

/** Imported by enabled writer features only; one auditing registration for their persistence units. */
@Configuration(proxyBeanMethods = false)
@EnableJpaAuditing
internal class JpaAuditingConfiguration
