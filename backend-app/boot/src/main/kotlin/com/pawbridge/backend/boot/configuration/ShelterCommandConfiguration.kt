package com.pawbridge.backend.boot.configuration

import com.pawbridge.backend.animal.api.AnimalCommandActors
import com.pawbridge.backend.animal.api.AnimalShelterMembers
import com.pawbridge.backend.animal.internal.command.infrastructure.search.SearchDocumentWriter
import com.pawbridge.backend.animal.internal.sheltercommand.application.ShelterCommands
import com.pawbridge.backend.animal.internal.sheltercommand.infrastructure.JpaShelterCommands
import com.pawbridge.backend.animal.internal.sheltercommand.web.ShelterCommandController
import com.pawbridge.backend.animal.internal.sheltercommand.web.ShelterCommandExceptionHandler
import com.pawbridge.backend.user.internal.shelter.infrastructure.JdbcAnimalShelterMembers
import jakarta.persistence.EntityManager
import javax.sql.DataSource
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.orm.jpa.JpaTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate

/** Three opt-in shelter writes sharing the existing Animal writer and User membership locks. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "pawbridge.shelter-commands", name = ["enabled"], havingValue = "true")
@Import(AnimalJpaWriterConfiguration::class)
class ShelterCommandConfiguration {
    @Bean
    fun shelterCommandMembers(
        @Qualifier("writeJdbc") jdbc: JdbcTemplate,
        @Qualifier("writeDataSource") source: DataSource,
    ): AnimalShelterMembers {
        if (jdbc.dataSource !== source) {
            throw IllegalArgumentException("Shelter membership checks must use the same writer DataSource")
        }
        return JdbcAnimalShelterMembers(jdbc)
    }

    @Bean
    fun shelterCommands(
        @Qualifier("animalCommandEntityManager") entities: EntityManager,
        @Qualifier("animalCommandJpaTransactionManager") manager: JpaTransactionManager,
        search: SearchDocumentWriter,
        actors: AnimalCommandActors,
        members: AnimalShelterMembers,
    ): ShelterCommands {
        val writes = TransactionTemplate(manager)
        writes.propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRED
        writes.isolationLevel = TransactionDefinition.ISOLATION_READ_COMMITTED
        return JpaShelterCommands(entities, manager, writes, search, actors, members)
    }

    @Bean
    fun shelterCommandController(shelters: ShelterCommands): ShelterCommandController {
        return ShelterCommandController(shelters)
    }

    @Bean
    fun shelterCommandExceptionHandler(): ShelterCommandExceptionHandler {
        return ShelterCommandExceptionHandler()
    }
}
