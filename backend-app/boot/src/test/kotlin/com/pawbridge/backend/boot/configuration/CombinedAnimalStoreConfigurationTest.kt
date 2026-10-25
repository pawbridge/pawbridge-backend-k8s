package com.pawbridge.backend.boot.configuration

import com.fasterxml.jackson.databind.ObjectMapper
import com.pawbridge.backend.animal.internal.command.infrastructure.repository.AnimalRepository
import com.pawbridge.backend.animal.internal.command.infrastructure.service.AnimalCommandService
import com.pawbridge.backend.animal.internal.lostsearch.infrastructure.LostSearchService
import com.pawbridge.backend.store.internal.directorder.application.DirectOrders
import com.pawbridge.backend.store.internal.directorder.infrastructure.JpaDirectOrders
import jakarta.persistence.EntityManager
import jakarta.persistence.EntityManagerFactory
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.verifyNoInteractions
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.autoconfigure.context.PropertyPlaceholderAutoConfiguration
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.data.auditing.AuditingHandler
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.orm.jpa.JpaTransactionManager
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean
import org.springframework.test.util.AopTestUtils
import org.springframework.test.util.ReflectionTestUtils
import java.util.concurrent.atomic.AtomicReference
import java.util.function.Supplier
import javax.sql.DataSource

class CombinedAnimalStoreConfigurationTest {
    @Test
    fun given__animal_commands_orders_and_photo_search_enabled__when__assembling__then__separate_reader_units_share_writer_inputs_and_close_all_factories() {
        val writer: DataSource = mock(DataSource::class.java)
        val reader: DataSource = mock(DataSource::class.java)
        val units = AtomicReference<List<EntityManagerFactory>>()
        ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(PropertyPlaceholderAutoConfiguration::class.java))
            .withUserConfiguration(
                AnimalCommandConfiguration::class.java,
                StoreDirectOrderConfiguration::class.java,
                AnimalLostSearchConfiguration::class.java,
            )
            .withBean("writeDataSource", DataSource::class.java, Supplier { writer })
            .withBean("writeJdbc", JdbcTemplate::class.java, Supplier { JdbcTemplate(writer) })
            .withBean("dataSource", DataSource::class.java, Supplier { reader })
            .withBean(ObjectMapper::class.java, Supplier { mock(ObjectMapper::class.java) })
            .withPropertyValues(
                "pawbridge.animal-commands.enabled=true",
                "pawbridge.store-direct-orders.enabled=true",
                "pawbridge.animal-lost-search.enabled=true",
                "lost-search.python-url=http://127.0.0.1:1",
                "python-ai-service.internal-api-key=combined-public-synthetic-key",
            )
            .run { context ->
                assertThat(context).hasNotFailed()
                    .hasSingleBean(JpaAuditingConfiguration::class.java)
                    .hasSingleBean(AuditingHandler::class.java)
                    .hasSingleBean(AnimalCommandService::class.java)
                    .hasSingleBean(DirectOrders::class.java)
                    .hasSingleBean(LostSearchService::class.java)
                units.set(context.getBeansOfType(EntityManagerFactory::class.java).values.toList())
                assertThat(units.get()).hasSize(3).doesNotHaveDuplicates()
                assertThat(context.getBeansOfType(DataSource::class.java)).hasSize(2)
                val animal = context.getBean("animalCommandEntityManagerFactory", EntityManagerFactory::class.java)
                val store = context.getBean("storeDirectOrderEntityManagerFactory", EntityManagerFactory::class.java)
                val lost = context.getBean("animalLostSearchEntityManagerFactory", EntityManagerFactory::class.java)
                for (prefix in listOf("animalCommand", "storeDirectOrder")) {
                    val factory = context.getBean(
                        "&" + prefix + "EntityManagerFactory",
                        LocalContainerEntityManagerFactoryBean::class.java,
                    )
                    assertThat(factory.dataSource).isSameAs(writer)
                    val transactions = context.getBean(prefix + "JpaTransactionManager", JpaTransactionManager::class.java)
                    assertThat(transactions.dataSource).isSameAs(writer)
                    assertThat(transactions.entityManagerFactory)
                        .isSameAs(if (prefix == "animalCommand") animal else store)
                }
                assertThat(
                    context.getBean("&animalLostSearchEntityManagerFactory", LocalContainerEntityManagerFactoryBean::class.java)
                        .dataSource,
                ).isSameAs(reader)
                val reads = context.getBean("animalLostSearchTransactions", JpaTransactionManager::class.java)
                assertThat(reads.dataSource).isSameAs(reader)
                assertThat(reads.entityManagerFactory).isSameAs(lost)
                assertThat(context.getBeansOfType(AnimalRepository::class.java)).hasSize(1)
                val writerAnimals: Any? =
                    ReflectionTestUtils.getField(context.getBean(AnimalCommandService::class.java), "animalRepository")
                val readerAnimals: Any? =
                    ReflectionTestUtils.getField(context.getBean(LostSearchService::class.java), "animals")
                assertThat(writerAnimals).isNotSameAs(readerAnimals)
                assertThat(repositoryEntityManager(writerAnimals!!).entityManagerFactory).isSameAs(animal)
                assertThat(repositoryEntityManager(readerAnimals!!).entityManagerFactory).isSameAs(lost)
                val orders = context.getBean(DirectOrders::class.java)
                assertThat(orders).isInstanceOf(JpaDirectOrders::class.java)
                val shared = context.getBean("storeDirectOrderEntityManager", EntityManager::class.java)
                assertThat(shared.entityManagerFactory).isSameAs(store)
                for (repository in listOf("writerOrders", "readerOrders")) {
                    assertThat(repositoryEntityManager(ReflectionTestUtils.getField(orders, repository)!!)).isSameAs(shared)
                }
                verifyNoInteractions(writer, reader)
            }
        assertThat(units.get()).hasSize(3).allSatisfy { factory -> assertThat(factory.isOpen).isFalse() }
        verifyNoInteractions(writer, reader)
    }

    private fun repositoryEntityManager(repository: Any): EntityManager {
        val target: Any = AopTestUtils.getUltimateTargetObject(repository)
        return ReflectionTestUtils.getField(target, "entityManager") as EntityManager
    }
}
