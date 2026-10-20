package com.pawbridge.backend.boot.configuration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pawbridge.backend.animal.internal.command.infrastructure.repository.AnimalRepository;
import com.pawbridge.backend.animal.internal.command.infrastructure.service.AnimalCommandService;
import com.pawbridge.backend.animal.internal.lostsearch.infrastructure.LostSearchService;
import com.pawbridge.backend.store.internal.directorder.application.DirectOrders;
import com.pawbridge.backend.store.internal.directorder.infrastructure.JpaDirectOrders;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.PropertyPlaceholderAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.data.auditing.AuditingHandler;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.util.ReflectionTestUtils;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class CombinedAnimalStoreConfigurationTest {
    @Test
    void given__animal_commands_orders_and_photo_search_enabled__when__assembling__then__separate_reader_units_share_writer_inputs_and_close_all_factories() {
        DataSource writer=mock(DataSource.class),reader=mock(DataSource.class);
        AtomicReference<List<EntityManagerFactory>> units=new AtomicReference<>();
        new ApplicationContextRunner().withConfiguration(AutoConfigurations.of(PropertyPlaceholderAutoConfiguration.class))
                .withUserConfiguration(AnimalCommandConfiguration.class,StoreDirectOrderConfiguration.class,AnimalLostSearchConfiguration.class)
                .withBean("writeDataSource",DataSource.class,()->writer)
                .withBean("writeJdbc",JdbcTemplate.class,()->new JdbcTemplate(writer))
                .withBean("dataSource",DataSource.class,()->reader)
                .withBean(ObjectMapper.class,()->mock(ObjectMapper.class))
                .withPropertyValues("pawbridge.animal-commands.enabled=true","pawbridge.store-direct-orders.enabled=true",
                        "pawbridge.animal-lost-search.enabled=true","lost-search.python-url=http://127.0.0.1:1",
                        "python-ai-service.internal-api-key=combined-public-synthetic-key")
                .run(context->{
                    assertThat(context).hasNotFailed().hasSingleBean(JpaAuditingConfiguration.class).hasSingleBean(AuditingHandler.class)
                            .hasSingleBean(AnimalCommandService.class).hasSingleBean(DirectOrders.class).hasSingleBean(LostSearchService.class);
                    units.set(List.copyOf(context.getBeansOfType(EntityManagerFactory.class).values()));
                    assertThat(units.get()).hasSize(3).doesNotHaveDuplicates();
                    assertThat(context.getBeansOfType(DataSource.class)).hasSize(2);
                    var animal=context.getBean("animalCommandEntityManagerFactory",EntityManagerFactory.class);
                    var store=context.getBean("storeDirectOrderEntityManagerFactory",EntityManagerFactory.class);
                    var lost=context.getBean("animalLostSearchEntityManagerFactory",EntityManagerFactory.class);
                    for(String prefix:List.of("animalCommand","storeDirectOrder")){
                        var factory=context.getBean("&"+prefix+"EntityManagerFactory",LocalContainerEntityManagerFactoryBean.class);
                        assertThat(factory.getDataSource()).isSameAs(writer);
                        var transactions=context.getBean(prefix+"JpaTransactionManager",JpaTransactionManager.class);
                        assertThat(transactions.getDataSource()).isSameAs(writer);
                        assertThat(transactions.getEntityManagerFactory()).isSameAs(prefix.equals("animalCommand")?animal:store);
                    }
                    assertThat(context.getBean("&animalLostSearchEntityManagerFactory",LocalContainerEntityManagerFactoryBean.class).getDataSource()).isSameAs(reader);
                    var reads=context.getBean("animalLostSearchTransactions",JpaTransactionManager.class);
                    assertThat(reads.getDataSource()).isSameAs(reader);assertThat(reads.getEntityManagerFactory()).isSameAs(lost);
                    assertThat(context.getBeansOfType(AnimalRepository.class)).hasSize(1);
                    Object writerAnimals=ReflectionTestUtils.getField(context.getBean(AnimalCommandService.class),"animalRepository");
                    Object readerAnimals=ReflectionTestUtils.getField(context.getBean(LostSearchService.class),"animals");
                    assertThat(writerAnimals).isNotSameAs(readerAnimals);
                    assertThat(repositoryEntityManager(writerAnimals).getEntityManagerFactory()).isSameAs(animal);
                    assertThat(repositoryEntityManager(readerAnimals).getEntityManagerFactory()).isSameAs(lost);
                    var orders=context.getBean(DirectOrders.class);assertThat(orders).isInstanceOf(JpaDirectOrders.class);
                    EntityManager shared=context.getBean("storeDirectOrderEntityManager",EntityManager.class);
                    assertThat(shared.getEntityManagerFactory()).isSameAs(store);
                    for(String repository:List.of("writerOrders","readerOrders"))
                        assertThat(repositoryEntityManager(ReflectionTestUtils.getField(orders,repository))).isSameAs(shared);
                    verifyNoInteractions(writer,reader);
                });
        assertThat(units.get()).hasSize(3).allSatisfy(factory->assertThat(factory.isOpen()).isFalse());
        verifyNoInteractions(writer,reader);
    }

    private static EntityManager repositoryEntityManager(Object repository){
        Object target=AopTestUtils.getUltimateTargetObject(repository);
        return (EntityManager)ReflectionTestUtils.getField(target,"entityManager");
    }
}
