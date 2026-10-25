package com.pawbridge.backend.boot.configuration

import com.fasterxml.jackson.databind.ObjectMapper
import com.pawbridge.backend.animal.internal.command.infrastructure.repository.AnimalRepository
import com.pawbridge.backend.animal.internal.command.infrastructure.service.AnimalCommandService
import com.pawbridge.backend.animal.internal.lostsearch.infrastructure.LostSearchService
import com.pawbridge.backend.store.internal.directorder.application.DirectOrders
import com.pawbridge.backend.store.internal.directorder.infrastructure.JpaDirectOrders
import io.mockk.confirmVerified
import io.mockk.excludeRecords
import io.mockk.mockk
import jakarta.persistence.EntityManager
import jakarta.persistence.EntityManagerFactory
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
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
    fun `동물 명령과 주문과 사진 검색이 켜졌을 때__조립하면__읽기 단위는 분리되고 쓰기 입력은 공유하며 모든 팩토리를 닫는다`() {
        // given
        val writer: DataSource = mockk<DataSource>()
        val reader: DataSource = mockk<DataSource>()
        val objectMapper = mockk<ObjectMapper>()
        val units = AtomicReference<List<EntityManagerFactory>>()

        excludeRecords { writer.hashCode() }
        excludeRecords { reader.hashCode() }

        // when
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
            .withBean(ObjectMapper::class.java, Supplier { objectMapper })
            .withPropertyValues(
                "pawbridge.animal-commands.enabled=true",
                "pawbridge.store-direct-orders.enabled=true",
                "pawbridge.animal-lost-search.enabled=true",
                "lost-search.python-url=http://127.0.0.1:1",
                "python-ai-service.internal-api-key=combined-public-synthetic-key",
            )
            .run { context ->
                // then
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
                confirmVerified(writer, reader)
            }
        assertThat(units.get()).hasSize(3).allSatisfy { factory -> assertThat(factory.isOpen).isFalse() }
        confirmVerified(writer, reader)
    }

    private fun repositoryEntityManager(repository: Any): EntityManager {
        val target: Any = AopTestUtils.getUltimateTargetObject(repository)
        return ReflectionTestUtils.getField(target, "entityManager") as EntityManager
    }
}
