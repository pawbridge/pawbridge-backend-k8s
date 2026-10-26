package com.pawbridge.backend.boot.configuration

import com.pawbridge.backend.animal.api.AnimalFavoriteCounts
import com.pawbridge.backend.animal.api.AnimalMemberReads
import com.pawbridge.backend.animal.internal.favorite.infrastructure.JdbcAnimalFavoriteCounts
import com.pawbridge.backend.user.internal.animalactivity.application.UserAnimalActivities
import com.pawbridge.backend.user.internal.animalactivity.infrastructure.JdbcUserAnimalActivities
import com.pawbridge.backend.user.internal.animalactivity.infrastructure.ModuleUserAnimalLookup
import com.pawbridge.backend.user.internal.animalactivity.web.UserAnimalActivitiesController
import com.pawbridge.backend.user.internal.animalactivity.web.UserAnimalActivitiesExceptionHandler
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.DependsOn
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(
    prefix = "pawbridge.user-animal-activities",
    name = ["enabled"],
    havingValue = "true",
    matchIfMissing = false,
)
@Import(UserAnimalActivitiesModuleConfiguration::class)
class UserAnimalActivitiesConfiguration {
    /** Same writer JDBC as the activities; the update joins the favorite transaction and opens none of its own. */
    @Bean
    fun userAnimalFavoriteCounts(@Qualifier("writeJdbc") jdbc: JdbcTemplate): AnimalFavoriteCounts {
        return JdbcAnimalFavoriteCounts(jdbc)
    }

    @Bean
    fun userAnimalActivities(
        @Qualifier("writeJdbc") jdbc: JdbcTemplate,
        @Qualifier("writeTransactionManager") manager: PlatformTransactionManager,
        animals: UserAnimalActivities.AnimalLookup,
        counts: AnimalFavoriteCounts,
    ): UserAnimalActivities {
        val write = TransactionTemplate(manager)
        val read = TransactionTemplate(manager)
        read.isReadOnly = true
        return JdbcUserAnimalActivities(jdbc, write, read, animals, counts)
    }

    @Bean
    fun userAnimalActivitiesController(activities: UserAnimalActivities): UserAnimalActivitiesController {
        return UserAnimalActivitiesController(activities)
    }

    @Bean
    fun userAnimalActivitiesExceptionHandler(): UserAnimalActivitiesExceptionHandler {
        return UserAnimalActivitiesExceptionHandler()
    }
}

/** Top-level and imported only through the enabled feature; no nested auto-registration. */
@Configuration(proxyBeanMethods = false)
@Import(AnimalModuleReadConfiguration::class)
internal class UserAnimalActivitiesModuleConfiguration {
    @Bean
    @DependsOn("animalModuleReads")
    fun moduleUserAnimalLookup(
        @Qualifier("animalModuleReads") animals: AnimalMemberReads,
    ): UserAnimalActivities.AnimalLookup {
        return ModuleUserAnimalLookup(animals)
    }
}
