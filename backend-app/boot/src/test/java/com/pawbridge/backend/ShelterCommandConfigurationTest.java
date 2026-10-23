package com.pawbridge.backend;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pawbridge.backend.animal.api.AnimalShelterMembers;
import com.pawbridge.backend.animal.internal.command.infrastructure.search.SearchDocumentWriter;
import com.pawbridge.backend.animal.internal.command.infrastructure.service.AnimalCommandService;
import com.pawbridge.backend.animal.internal.discovery.infrastructure.KoreanSearchAnalyzer;
import com.pawbridge.backend.animal.internal.sheltercommand.application.ShelterCommands;
import com.pawbridge.backend.animal.internal.sheltercommand.web.ShelterCommandController;
import com.pawbridge.backend.boot.configuration.AnimalCommandConfiguration;
import com.pawbridge.backend.boot.configuration.ShelterCommandConfiguration;
import jakarta.persistence.EntityManagerFactory;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.PropertyPlaceholderAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ShelterCommandConfigurationTest {
    private static ApplicationContextRunner runner() {
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(PropertyPlaceholderAutoConfiguration.class))
                .withUserConfiguration(AnimalCommandConfiguration.class,ShelterCommandConfiguration.class);
    }
    private static ApplicationContextRunner writer(DataSource source) {
        return runner().withBean("writeDataSource",DataSource.class,()->source)
                .withBean("writeJdbc",JdbcTemplate.class,()->new JdbcTemplate(source))
                .withBean(ObjectMapper.class,ObjectMapper::new);
    }

    @ParameterizedTest @ValueSource(strings={"absent","false","on","yes","1","unknown"})
    void given__neither_writer_enabled__when__assembling_without_inputs__then__create_no_writer_or_auditing_or_routes(String flag) {
        var selected=runner();
        if(!flag.equals("absent")) selected=selected.withPropertyValues("pawbridge.shelter-commands.enabled="+flag);
        selected.run(context->assertThat(context).hasNotFailed().doesNotHaveBean(EntityManagerFactory.class)
                .doesNotHaveBean(JpaTransactionManager.class).doesNotHaveBean(ShelterCommands.class)
                .doesNotHaveBean(AnimalCommandService.class).doesNotHaveBean(KoreanSearchAnalyzer.class)
                .doesNotHaveBean("jpaAuditingHandler"));
    }

    @ParameterizedTest @ValueSource(booleans={false,true})
    void given__shelter_writer_and_optional_animal_commands__when__assembling__then__share_one_bounded_writer_and_validate_joined_isolation(boolean animals) throws Exception {
        DataSource source=mock(DataSource.class);
        AtomicReference<EntityManagerFactory> owned=new AtomicReference<>();
        AtomicReference<KoreanSearchAnalyzer> analyzer=new AtomicReference<>();
        writer(source).withPropertyValues("pawbridge.shelter-commands.enabled=TRUE","pawbridge.animal-commands.enabled="+animals)
                .run(context->{
                    assertThat(context).hasNotFailed().hasSingleBean(ShelterCommands.class).hasSingleBean(ShelterCommandController.class);
                    assertThat(context.getBeansOfType(EntityManagerFactory.class)).hasSize(1);
                    assertThat(context.getBeansOfType(JpaTransactionManager.class)).hasSize(1);
                    var factory=context.getBean("animalCommandEntityManagerFactory",EntityManagerFactory.class);owned.set(factory);
                    var manager=context.getBean("animalCommandJpaTransactionManager",JpaTransactionManager.class);
                    assertThat(manager.getDataSource()).isSameAs(source);
                    assertThat(manager.getEntityManagerFactory()).isSameAs(factory);
                    assertThat(manager.getDefaultTimeout()).isEqualTo(15);
                    assertThat(manager.isValidateExistingTransaction()).isTrue();
                    var writes=(TransactionTemplate)ReflectionTestUtils.getField(context.getBean(ShelterCommands.class),"writes");
                    assertThat(writes.getTransactionManager()).isSameAs(manager);
                    assertThat(writes.getIsolationLevel()).isEqualTo(TransactionDefinition.ISOLATION_READ_COMMITTED);
                    assertThat(writes.getPropagationBehavior()).isEqualTo(TransactionDefinition.PROPAGATION_REQUIRED);
                    assertThat(writes.isReadOnly()).isFalse();
                    assertThat(context.getBean(SearchDocumentWriter.class).dataSource()).isSameAs(source);
                    assertThat(((JdbcTemplate)ReflectionTestUtils.getField(context.getBean(AnimalShelterMembers.class),"jdbc")).getDataSource()).isSameAs(source);
                    assertThat(context.getBeansOfType(AnimalCommandService.class)).hasSize(animals?1:0);
                    assertThat(context.getBeansOfType(KoreanSearchAnalyzer.class)).hasSize(1);
                    assertThat(context.getBeansOfType(org.springframework.data.auditing.AuditingHandler.class)).hasSize(1);
                    analyzer.set(context.getBean(KoreanSearchAnalyzer.class));
                    verify(source,never()).getConnection();
                });
        assertThat(owned.get()).isNotNull();assertThat(owned.get().isOpen()).isFalse();
        assertThat(analyzer.get()).isNotNull();verify(source,never()).getConnection();
    }

    @Test
    void given__different_user_lookup_source__when__assembling_shelter_writes__then__reject_before_any_database_access() throws Exception {
        DataSource source=mock(DataSource.class),other=mock(DataSource.class);
        runner().withPropertyValues("pawbridge.shelter-commands.enabled=true")
                .withBean("writeDataSource",DataSource.class,()->source)
                .withBean("writeJdbc",JdbcTemplate.class,()->new JdbcTemplate(other))
                .withBean(ObjectMapper.class,ObjectMapper::new).run(context->{
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).hasRootCauseInstanceOf(IllegalArgumentException.class);
                });
        verify(source,never()).getConnection();verify(other,never()).getConnection();
    }
}
