package com.pawbridge.backend.boot.configuration

import com.pawbridge.backend.animal.api.AnimalShelterReads
import com.pawbridge.backend.animal.internal.shelter.infrastructure.MembershipLockedAnimalShelterReads
import com.pawbridge.backend.user.internal.shelter.application.ShelterMembership
import com.pawbridge.backend.user.internal.shelter.infrastructure.JdbcShelterMembership
import com.pawbridge.backend.user.internal.shelter.infrastructure.ModuleShelterLookup
import com.pawbridge.backend.user.internal.shelter.web.AdminShelterApplicationController
import com.pawbridge.backend.user.internal.shelter.web.AdminShelterMemberController
import com.pawbridge.backend.user.internal.shelter.web.ShelterApplicationController
import com.pawbridge.backend.user.internal.shelter.web.ShelterMembershipExceptionHandler
import java.time.Clock
import javax.sql.DataSource
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(
    prefix = "pawbridge.shelter-membership",
    name = ["enabled"],
    havingValue = "true",
    matchIfMissing = false,
)
@Import(ShelterMembershipModuleConfiguration::class)
class ShelterMembershipConfiguration {
    @Bean
    fun shelterMembership(
        @Qualifier("writeJdbc") jdbc: JdbcTemplate,
        @Qualifier("writeTransactionManager") manager: PlatformTransactionManager,
        shelters: ShelterMembership.ShelterLookup,
        @Qualifier("readClock") clock: Clock,
    ): ShelterMembership {
        val write = TransactionTemplate(manager)
        val read = TransactionTemplate(manager)
        read.isReadOnly = true
        val statistics = TransactionTemplate(manager)
        statistics.isReadOnly = true
        statistics.isolationLevel = TransactionDefinition.ISOLATION_REPEATABLE_READ
        return JdbcShelterMembership(jdbc, write, read, statistics, shelters, clock)
    }

    @Bean
    fun shelterApplicationController(memberships: ShelterMembership): ShelterApplicationController {
        return ShelterApplicationController(memberships)
    }

    @Bean
    fun adminShelterApplicationController(memberships: ShelterMembership): AdminShelterApplicationController {
        return AdminShelterApplicationController(memberships)
    }

    @Bean
    fun adminShelterMemberController(memberships: ShelterMembership): AdminShelterMemberController {
        return AdminShelterMemberController(memberships)
    }

    @Bean
    fun shelterMembershipExceptionHandler(): ShelterMembershipExceptionHandler {
        return ShelterMembershipExceptionHandler()
    }
}

/** Top-level and imported only through the enabled feature; no nested auto-registration. */
@Configuration(proxyBeanMethods = false)
@Import(AnimalModuleReadConfiguration::class)
internal class ShelterMembershipModuleConfiguration {
    @Bean
    fun moduleShelterLookup(
        @Qualifier("animalModuleReads") animals: AnimalShelterReads,
        @Qualifier("writeJdbc") jdbc: JdbcTemplate,
        @Qualifier("writeDataSource") sources: ObjectProvider<DataSource>,
        @Value("\${pawbridge.shelter-commands.enabled:false}") shelterCommands: String,
    ): ShelterMembership.ShelterLookup {
        if (!"true".equals(shelterCommands, ignoreCase = true)) {
            return ModuleShelterLookup(animals)
        }
        val source: DataSource = sources.getObject()
        if (jdbc.dataSource !== source) {
            throw IllegalArgumentException("Shelter membership module requires the shared writer data source")
        }
        return ModuleShelterLookup(MembershipLockedAnimalShelterReads(animals, jdbc))
    }
}
