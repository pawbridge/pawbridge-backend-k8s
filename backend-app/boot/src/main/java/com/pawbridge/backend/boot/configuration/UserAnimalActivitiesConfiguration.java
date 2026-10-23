package com.pawbridge.backend.boot.configuration;

import com.pawbridge.backend.animal.api.AnimalFavoriteCounts;
import com.pawbridge.backend.animal.api.AnimalMemberReads;
import com.pawbridge.backend.animal.internal.favorite.infrastructure.JdbcAnimalFavoriteCounts;
import com.pawbridge.backend.user.internal.animalactivity.application.UserAnimalActivities;
import com.pawbridge.backend.user.internal.animalactivity.infrastructure.JdbcUserAnimalActivities;
import com.pawbridge.backend.user.internal.animalactivity.infrastructure.ModuleUserAnimalLookup;
import com.pawbridge.backend.user.internal.animalactivity.web.UserAnimalActivitiesController;
import com.pawbridge.backend.user.internal.animalactivity.web.UserAnimalActivitiesExceptionHandler;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(
        prefix = "pawbridge.user-animal-activities",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = false)
@Import(UserAnimalActivitiesModuleConfiguration.class)
public class UserAnimalActivitiesConfiguration {

    /** Same writer JDBC as the activities; the update joins the favorite transaction and opens none of its own. */
    @Bean
    AnimalFavoriteCounts userAnimalFavoriteCounts(@Qualifier("writeJdbc") JdbcTemplate jdbc) {
        return new JdbcAnimalFavoriteCounts(jdbc);
    }

    @Bean
    UserAnimalActivities userAnimalActivities(
            @Qualifier("writeJdbc") JdbcTemplate jdbc,
            @Qualifier("writeTransactionManager") PlatformTransactionManager manager,
            UserAnimalActivities.AnimalLookup animals,
            AnimalFavoriteCounts counts) {
        var write = new TransactionTemplate(manager);
        var read = new TransactionTemplate(manager);
        read.setReadOnly(true);
        return new JdbcUserAnimalActivities(jdbc, write, read, animals, counts);
    }

    @Bean
    UserAnimalActivitiesController userAnimalActivitiesController(UserAnimalActivities activities) {
        return new UserAnimalActivitiesController(activities);
    }

    @Bean
    UserAnimalActivitiesExceptionHandler userAnimalActivitiesExceptionHandler() {
        return new UserAnimalActivitiesExceptionHandler();
    }
}

/** Top-level and imported only through the enabled feature; no nested auto-registration. */
@Configuration(proxyBeanMethods = false)
@Import(AnimalModuleReadConfiguration.class)
class UserAnimalActivitiesModuleConfiguration {

    @Bean
    @DependsOn("animalModuleReads")
    UserAnimalActivities.AnimalLookup moduleUserAnimalLookup(
            @Qualifier("animalModuleReads") AnimalMemberReads animals) {
        return new ModuleUserAnimalLookup(animals);
    }
}
