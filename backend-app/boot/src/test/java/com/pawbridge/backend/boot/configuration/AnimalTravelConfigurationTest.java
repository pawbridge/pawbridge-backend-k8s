package com.pawbridge.backend.boot.configuration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pawbridge.backend.animal.internal.travel.application.PetTravel;
import com.pawbridge.backend.animal.internal.travel.web.PetTravelController;
import jakarta.persistence.EntityManagerFactory;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.PropertyPlaceholderAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class AnimalTravelConfigurationTest {
    ApplicationContextRunner runner() {
        return new ApplicationContextRunner().withConfiguration(AutoConfigurations.of(PropertyPlaceholderAutoConfiguration.class))
                .withUserConfiguration(AnimalTravelConfiguration.class);
    }

    @ParameterizedTest @ValueSource(strings={"absent","false","on","yes","1","unknown"})
    void feature_is_default_off_and_aliases_do_not_instantiate_any_catalog_or_require_reader_inputs(String flag) {
        var context=runner();if(!flag.equals("absent"))context=context.withPropertyValues("pawbridge.animal-travel.enabled="+flag);
        context.run(app->assertThat(app).hasNotFailed().doesNotHaveBean(PetTravel.class).doesNotHaveBean(PetTravelController.class)
                .doesNotHaveBean(TransactionTemplate.class).doesNotHaveBean(DataSource.class).doesNotHaveBean(EntityManagerFactory.class));
    }

    @ParameterizedTest @ValueSource(strings={"absent","elasticsearch","mysql","unknown"})
    void enabled_feature_requires_explicit_postgresql_before_reader_access(String backend) {
        var context=runner().withPropertyValues("pawbridge.animal-travel.enabled=true");
        if(!backend.equals("absent"))context=context.withPropertyValues("pawbridge.animal-query.backend="+backend);
        context.run(app->{assertThat(app).hasFailed();assertThat(app.getStartupFailure()).hasRootCauseMessage("Travel catalog requires explicit PostgreSQL selection");});
    }

    @Test void catalog_reuses_existing_reader_and_copies_only_list_isolation_without_new_pools_or_models() {
        DataSource source=mock(DataSource.class);JdbcTransactionManager manager=mock(JdbcTransactionManager.class);
        var jdbc=new JdbcTemplate(source);jdbc.setQueryTimeout(3);
        var reads=new TransactionTemplate(manager);reads.setReadOnly(true);reads.setTimeout(3);
        runner().withBean("dataSource",DataSource.class,()->source).withBean("transactionManager",JdbcTransactionManager.class,()->manager)
                .withBean("readJdbc",JdbcTemplate.class,()->jdbc).withBean("readOnlyTransaction",TransactionTemplate.class,()->reads)
                .withBean(ObjectMapper.class,ObjectMapper::new)
                .withPropertyValues("pawbridge.animal-travel.enabled=TrUe","pawbridge.animal-query.backend=postgresql")
                .run(app->{
                    assertThat(app).hasNotFailed().hasSingleBean(PetTravel.class).hasSingleBean(PetTravelController.class)
                            .hasSingleBean(DataSource.class).hasSingleBean(JdbcTransactionManager.class).doesNotHaveBean(EntityManagerFactory.class)
                            .doesNotHaveBean("writeDataSource");
                    var list=app.getBean("animalTravelListReadOnlyTransaction",TransactionTemplate.class);
                    assertThat(list).isNotSameAs(reads);assertThat(list.getTransactionManager()).isSameAs(manager);
                    assertThat(list.getIsolationLevel()).isEqualTo(TransactionDefinition.ISOLATION_REPEATABLE_READ);
                    assertThat(list.isReadOnly()).isTrue();assertThat(list.getTimeout()).isEqualTo(3);
                    assertThat(reads.getIsolationLevel()).isEqualTo(TransactionDefinition.ISOLATION_DEFAULT);
                    assertThat(reads.getTimeout()).isEqualTo(3);assertThat(reads.isReadOnly()).isTrue();
                    Object service=app.getBean(PetTravel.class);
                    assertThat(ReflectionTestUtils.getField(service,"reads")).isSameAs(reads);
                    assertThat(ReflectionTestUtils.getField(service,"repeatableReads")).isSameAs(list);
                    Object repository=ReflectionTestUtils.getField(service,"repository");
                    assertThat(ReflectionTestUtils.getField(repository,"jdbc")).isSameAs(jdbc);
                    verifyNoInteractions(source);
                    verify(manager,never()).getTransaction(org.mockito.ArgumentMatchers.any());
                });
    }
}
