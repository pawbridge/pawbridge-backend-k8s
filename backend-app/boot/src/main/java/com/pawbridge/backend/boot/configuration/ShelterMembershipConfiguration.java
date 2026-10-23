package com.pawbridge.backend.boot.configuration;

import com.pawbridge.backend.animal.api.AnimalShelterReads;
import com.pawbridge.backend.animal.internal.shelter.infrastructure.MembershipLockedAnimalShelterReads;
import com.pawbridge.backend.user.internal.shelter.application.ShelterMembership;
import com.pawbridge.backend.user.internal.shelter.infrastructure.JdbcShelterMembership;
import com.pawbridge.backend.user.internal.shelter.infrastructure.ModuleShelterLookup;
import com.pawbridge.backend.user.internal.shelter.web.AdminShelterApplicationController;
import com.pawbridge.backend.user.internal.shelter.web.AdminShelterMemberController;
import com.pawbridge.backend.user.internal.shelter.web.ShelterApplicationController;
import com.pawbridge.backend.user.internal.shelter.web.ShelterMembershipExceptionHandler;
import java.time.Clock;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(
        prefix = "pawbridge.shelter-membership",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = false)
@Import(ShelterMembershipModuleConfiguration.class)
public class ShelterMembershipConfiguration {

    @Bean
    ShelterMembership shelterMembership(
            @Qualifier("writeJdbc") JdbcTemplate jdbc,
            @Qualifier("writeTransactionManager") PlatformTransactionManager manager,
            ShelterMembership.ShelterLookup shelters,
            @Qualifier("readClock") Clock clock) {
        var write = new TransactionTemplate(manager);
        var read = new TransactionTemplate(manager);
        read.setReadOnly(true);
        var statistics = new TransactionTemplate(manager);
        statistics.setReadOnly(true);
        statistics.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        return new JdbcShelterMembership(jdbc, write, read, statistics, shelters, clock);
    }

    @Bean
    ShelterApplicationController shelterApplicationController(ShelterMembership memberships) {
        return new ShelterApplicationController(memberships);
    }

    @Bean
    AdminShelterApplicationController adminShelterApplicationController(ShelterMembership memberships) {
        return new AdminShelterApplicationController(memberships);
    }

    @Bean
    AdminShelterMemberController adminShelterMemberController(ShelterMembership memberships) {
        return new AdminShelterMemberController(memberships);
    }

    @Bean
    ShelterMembershipExceptionHandler shelterMembershipExceptionHandler() {
        return new ShelterMembershipExceptionHandler();
    }
}

/** Top-level and imported only through the enabled feature; no nested auto-registration. */
@Configuration(proxyBeanMethods = false)
@Import(AnimalModuleReadConfiguration.class)
class ShelterMembershipModuleConfiguration {

    @Bean
    ShelterMembership.ShelterLookup moduleShelterLookup(
            @Qualifier("animalModuleReads") AnimalShelterReads animals,
            @Qualifier("writeJdbc") JdbcTemplate jdbc,
            @Qualifier("writeDataSource") ObjectProvider<DataSource> sources,
            @Value("${pawbridge.shelter-commands.enabled:false}") String shelterCommands) {
        if (!"true".equalsIgnoreCase(shelterCommands)) {
            return new ModuleShelterLookup(animals);
        }
        DataSource source = sources.getObject();
        if (jdbc.getDataSource() != source) {
            throw new IllegalArgumentException("Shelter membership module requires the shared writer data source");
        }
        return new ModuleShelterLookup(new MembershipLockedAnimalShelterReads(animals, jdbc));
    }
}
