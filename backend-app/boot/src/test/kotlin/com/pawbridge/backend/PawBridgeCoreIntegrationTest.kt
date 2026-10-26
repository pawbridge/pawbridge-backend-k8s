package com.pawbridge.backend

import com.pawbridge.backend.animal.api.AnimalCommandActors
import com.pawbridge.backend.animal.api.AnimalMemberReads
import com.pawbridge.backend.animal.internal.command.infrastructure.service.AnimalCommandService
import com.pawbridge.backend.animal.internal.command.web.AnimalCommandController
import com.pawbridge.backend.animal.internal.discovery.infrastructure.KoreanSearchAnalyzer
import com.pawbridge.backend.boot.configuration.PostWriteSettings
import com.pawbridge.backend.boot.configuration.ReadDatabaseSettings
import com.pawbridge.backend.community.internal.post.application.ReadPost
import com.pawbridge.backend.store.api.StoreMemberReads
import com.pawbridge.backend.store.api.StoreOrderActors
import com.pawbridge.backend.store.internal.catalog.infrastructure.KoreanSearchTerms
import com.pawbridge.backend.store.internal.directorder.application.DirectOrders
import com.pawbridge.backend.store.internal.directorder.infrastructure.JpaDirectOrders
import com.pawbridge.backend.store.internal.directorder.web.DirectOrderController
import com.pawbridge.backend.store.internal.memberread.infrastructure.JpaStoreMemberReads
import com.pawbridge.backend.user.api.UserNicknameQuery
import com.pawbridge.backend.user.internal.animalactivity.application.UserAnimalActivities
import com.pawbridge.backend.user.internal.animalactivity.infrastructure.JdbcUserAnimalActivities
import com.pawbridge.backend.user.internal.animalactivity.infrastructure.ModuleUserAnimalLookup
import com.pawbridge.backend.user.internal.storemypage.application.StoreMyPage
import com.pawbridge.backend.user.internal.storemypage.infrastructure.ModuleStoreMyPage
import com.zaxxer.hikari.HikariDataSource
import com.zaxxer.hikari.metrics.IMetricsTracker
import com.zaxxer.hikari.metrics.MetricsTrackerFactory
import jakarta.persistence.EntityManager
import jakarta.persistence.EntityManagerFactory
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.config.BeanPostProcessor
import org.springframework.boot.test.context.runner.WebApplicationContextRunner
import org.springframework.data.redis.connection.RedisConnectionFactory
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.support.JdbcTransactionManager
import org.springframework.orm.jpa.JpaTransactionManager
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity
import org.springframework.security.web.SecurityFilterChain
import org.springframework.test.util.AopTestUtils
import org.springframework.test.util.ReflectionTestUtils
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.util.concurrent.atomic.AtomicInteger
import javax.sql.DataSource

/** Actual Main imports and actual persistence/security consumers, without a database fixture or business substitutes. */
class PawBridgeCoreIntegrationTest {
    @Test
    fun `명시적 코어 모듈과 writer가 주어졌을 때__main을 조립하고 보안이 거부하면__이름 있는 자원을 빌리지 않고 공유해 닫는다`() {
        // given
        val resources = Resources()

        // when
        runner(resources).run { context ->
            // then
            assertThat(context).hasNotFailed()
            // The public chain plus the management-port chain, which never matches a request on the main port.
            assertThat(context.getBeansOfType(SecurityFilterChain::class.java))
                .containsOnlyKeys("postReadSecurity", "managementSecurity")
            assertThat(context.getBeansOfType(DataSource::class.java)).containsOnlyKeys("dataSource")
            assertThat(context.getBeansOfType(EntityManagerFactory::class.java)).containsOnlyKeys(
                "storeMemberEntityManagerFactory",
                "animalCommandEntityManagerFactory",
                "storeDirectOrderEntityManagerFactory",
            )
            assertThat(context.getBeansOfType(PlatformTransactionManager::class.java)).containsOnlyKeys(
                "transactionManager",
                "writeTransactionManager",
                "storeMemberJpaTransactionManager",
                "animalCommandJpaTransactionManager",
                "storeDirectOrderJpaTransactionManager",
            )
            val reader = context.getBean("dataSource", HikariDataSource::class.java)
            val writer = context.getBean("writeDataSource", HikariDataSource::class.java)
            // One shared pool: the historical writer name is an alias of the same instance.
            assertThat(writer).isSameAs(reader)
            assertThat(reader.isReadOnly).isFalse()
            for (prefix in listOf("storeMember", "animalCommand", "storeDirectOrder")) {
                val expected: DataSource = reader
                val factory = context.getBean(prefix + "EntityManagerFactory", EntityManagerFactory::class.java)
                assertThat(
                    context.getBean("&" + prefix + "EntityManagerFactory", LocalContainerEntityManagerFactoryBean::class.java)
                        .dataSource,
                ).isSameAs(expected)
                val manager = context.getBean(prefix + "JpaTransactionManager", JpaTransactionManager::class.java)
                assertThat(manager.dataSource).isSameAs(expected)
                assertThat(manager.entityManagerFactory).isSameAs(factory)
            }
            assertThat(context.getBean("transactionManager", JdbcTransactionManager::class.java).dataSource).isSameAs(reader)
            assertThat(context.getBean("writeTransactionManager", JdbcTransactionManager::class.java).dataSource)
                .isSameAs(writer)

            // Public module APIs must be the instances consumed by real User adapters and writer facades.
            val activities = context.getBean(UserAnimalActivities::class.java)
            assertThat(activities).isInstanceOf(JdbcUserAnimalActivities::class.java)
            val lookup = field(activities, "animals")
            assertThat(lookup).isInstanceOf(ModuleUserAnimalLookup::class.java)
            val animalReads = context.getBean(AnimalMemberReads::class.java)
            assertThat(field(lookup, "animals")).isSameAs(animalReads)
            assertThat((field(animalReads, "jdbc") as NamedParameterJdbcTemplate).jdbcTemplate.dataSource).isSameAs(reader)
            assertThat((field(animalReads, "reads") as TransactionTemplate).transactionManager)
                .isSameAs(context.getBean("transactionManager"))
            assertThat(field(activities, "jdbc")).isSameAs(context.getBean("writeJdbc"))
            assertThat((field(activities, "write") as TransactionTemplate).transactionManager)
                .isSameAs(context.getBean("writeTransactionManager"))

            val myPage = context.getBean(StoreMyPage::class.java)
            assertThat(myPage).isInstanceOf(ModuleStoreMyPage::class.java)
            val storeReads = context.getBean(StoreMemberReads::class.java)
            assertThat(storeReads).isInstanceOf(JpaStoreMemberReads::class.java)
            assertThat(field(myPage, "store")).isSameAs(storeReads)
            assertThat(field(myPage, "read")).isSameAs(context.getBean("readOnlyTransaction"))
            assertThat((field(storeReads, "reads") as TransactionTemplate).transactionManager)
                .isSameAs(context.getBean("storeMemberJpaTransactionManager"))
            assertThat(repositoryEntityManager(field(storeReads, "orders")).entityManagerFactory)
                .isSameAs(context.getBean("storeMemberEntityManagerFactory"))
            assertThat(field(context.getBean(ReadPost::class.java), "userNicknameQuery"))
                .isSameAs(context.getBean(UserNicknameQuery::class.java))
            assertThat(field(context.getBean(ReadPost::class.java), "postReader")).isSameAs(context.getBean("postReader"))

            val animalCommands = context.getBean(AnimalCommandService::class.java)
            val animalActors = context.getBean(AnimalCommandActors::class.java)
            assertThat(field(animalCommands, "actors")).isSameAs(animalActors)
            assertThat(field(animalActors, "jdbc")).isSameAs(context.getBean("writeJdbc"))
            assertThat((field(animalCommands, "writes") as TransactionTemplate).transactionManager)
                .isSameAs(context.getBean("animalCommandJpaTransactionManager"))
            assertThat(repositoryEntityManager(field(animalCommands, "animalRepository")).entityManagerFactory)
                .isSameAs(context.getBean("animalCommandEntityManagerFactory"))
            assertThat(field(context.getBean(AnimalCommandController::class.java), "animals")).isSameAs(animalCommands)
            val orders = context.getBean(DirectOrders::class.java)
            assertThat(orders).isInstanceOf(JpaDirectOrders::class.java)
            val orderActors = context.getBean(StoreOrderActors::class.java)
            assertThat(field(orders, "actors")).isSameAs(orderActors)
            assertThat(field(orderActors, "jdbc")).isSameAs(context.getBean("writeJdbc"))
            assertThat(field(orders, "writes")).isSameAs(context.getBean("storeDirectOrderWrites"))
            assertThat(field(orders, "reads")).isSameAs(context.getBean("storeDirectOrderReads"))
            assertThat((field(orders, "writes") as TransactionTemplate).transactionManager)
                .isSameAs(context.getBean("storeDirectOrderJpaTransactionManager"))
            assertThat((field(orders, "reads") as TransactionTemplate).transactionManager)
                .isSameAs(context.getBean("storeDirectOrderJpaTransactionManager"))
            assertThat(repositoryEntityManager(field(orders, "writerOrders")).entityManagerFactory)
                .isSameAs(context.getBean("storeDirectOrderEntityManagerFactory"))
            assertThat(repositoryEntityManager(field(orders, "readerOrders")).entityManagerFactory)
                .isSameAs(context.getBean("storeDirectOrderEntityManagerFactory"))
            assertThat(field(context.getBean(DirectOrderController::class.java), "orders")).isSameAs(orders)

            // These actual actor guards run before SQL when no owning writer transaction exists.
            assertThatThrownBy { animalActors.find(7) }
                .isInstanceOf(IllegalStateException::class.java)
                .hasMessageContaining("shared writer transaction")
            assertThatThrownBy { orderActors.currentForWrite(7) }
                .isInstanceOf(IllegalStateException::class.java)
                .hasMessageContaining("shared Store order transaction")
            val mvc = MockMvcBuilders.webAppContextSetup(context.sourceApplicationContext)
                .apply<DefaultMockMvcBuilder>(springSecurity())
                .build()
            for (path in listOf("/api/v1/users/me", "/api/v1/users/me/favorite-animals", "/api/v1/users/me/orders")) {
                val result = mvc.perform(get(path)).andExpect(status().isUnauthorized()).andReturn()
                assertThat(result.handler).isNull()
            }
            for (path in listOf("/api/v1/animals", "/api/v1/orders/direct")) {
                val result = mvc.perform(post(path).contentType("application/json").content("{}"))
                    .andExpect(status().isUnauthorized())
                    .andReturn()
                assertThat(result.handler).isNull()
            }
            val roleDenied = mvc.perform(
                get("/api/v1/admin/users").header("Authorization", "Bearer " + F3TestInputs.token(7, "ROLE_USER")),
            )
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("관리자 권한이 필요합니다."))
                .andReturn()
            assertThat(roleDenied.handler).isNull()
            val methodDenied = mvc.perform(
                put("/api/v1/orders/direct").header("Authorization", "Bearer " + F3TestInputs.token(7, "ROLE_ADMIN")),
            )
                .andExpect(status().isForbidden())
                .andReturn()
            assertThat(methodDenied.handler).isNull()
            assertThat(context.getBeansOfType(ScheduledAnnotationBeanPostProcessor::class.java)).isEmpty()
            assertThat(context.containsBean("userAnimalHttpClient")).isFalse()
            assertThat(context.containsBean("userStoreHttpClient")).isFalse()
            assertThat(context.containsBean("animalLostSearchEntityManagerFactory")).isFalse()
            assertThat(context.getBeansOfType(RedisConnectionFactory::class.java)).isEmpty()
            assertThat(context.getBeansOfType(KoreanSearchAnalyzer::class.java)).hasSize(1)
            assertThat(context.getBeansOfType(KoreanSearchTerms::class.java)).hasSize(1)
            assertThat(resources.borrowed.get()).isZero()
            assertThat(resources.timedOut.get()).isZero()
            for (pool in resources.pools) {
                assertThat(pool.hikariPoolMXBean.activeConnections).isZero()
                assertThat(pool.hikariPoolMXBean.totalConnections).isZero()
            }
        }

        // then (after the context closes)
        assertThat(resources.pools).hasSize(1).allSatisfy { pool -> assertThat(pool.isClosed).isTrue() }
        assertThat(resources.factories).hasSize(3).allSatisfy { factory -> assertThat(factory.isOpen).isFalse() }
        assertThat(resources.animalAnalyzers).hasSize(1)
        assertThatThrownBy { resources.animalAnalyzers[0].tokens("진돗개") }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("closed")
        assertThat(resources.storeAnalyzers).hasSize(1)
        assertThatThrownBy { resources.storeAnalyzers[0].query("강아지 사료") }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("closed")
        assertThat(resources.borrowed.get()).isZero()
        assertThat(resources.timedOut.get()).isZero()
    }

    private fun runner(resources: Resources): WebApplicationContextRunner {
        val values = F3TestInputs.values()
        values["PAWBRIDGE_DB_URL"] = "jdbc:postgresql://127.0.0.1:1/pawbridge_main_synthetic"
        val read = ReadDatabaseSettings.fromEnvironment(values::get)
        val write = PostWriteSettings.fromEnvironment(values::get)
        return WebApplicationContextRunner().withUserConfiguration(PawBridgeApplication::class.java)
            .withInitializer { context ->
                context.beanFactory.registerSingleton("readDatabaseSettings", read)
                context.beanFactory.registerSingleton("postWriteSettings", write)
                context.beanFactory.addBeanPostProcessor(resources)
            }
            .withPropertyValues(
                "spring.sql.init.mode=never",
                "spring.jmx.enabled=false",
                "pawbridge.user-animal-activities.enabled=true",
                "pawbridge.user-store-mypage.enabled=true",
                "pawbridge.animal-commands.enabled=true",
                "pawbridge.store-direct-orders.enabled=true",
                "pawbridge.auth.google-enabled=false",
                "pawbridge.auth.sessions-enabled=false",
                "pawbridge.chat.enabled=false",
                "pawbridge.jobs.cleanup-enabled=false",
                "pawbridge.user-maintenance.enabled=false",
                "pawbridge.community-maintenance.enabled=false",
                "pawbridge.visits.enabled=false",
                "pawbridge.visits.collection-enabled=false",
                "pawbridge.animal-query.projection-schedule-enabled=false",
                "apms.batch.schedule-enabled=false",
                "apms-photo-archive.enabled=false",
                "tourapi.enabled=false",
                "tourapi.schedule-enabled=false",
                "lost-gallery-feed.enabled=false",
                "pawbridge.animal-lost-search.enabled=false",
                "pawbridge.store-carts.enabled=false",
                "pawbridge.store-catalog.enabled=false",
                "pawbridge.store-order-administration.enabled=false",
                "pawbridge.animal-discovery.enabled=false",
                "pawbridge.animal-recommendations.enabled=false",
                "pawbridge.animal-shelters.enabled=false",
                "pawbridge.animal-statistics.enabled=false",
                "pawbridge.animal-travel.enabled=false",
                "pawbridge.shelter-commands.enabled=false",
                "pawbridge.animal-reports.enabled=false",
                "pawbridge.store-wishlist.enabled=false",
                "pawbridge.shelter-membership.enabled=false",
                "pawbridge.home-videos.management-enabled=false",
            )
    }

    private fun field(bean: Any?, name: String): Any {
        return ReflectionTestUtils.getField(bean!!, name)!!
    }

    private fun repositoryEntityManager(repository: Any): EntityManager {
        return field(AopTestUtils.getUltimateTargetObject<Any>(repository), "entityManager") as EntityManager
    }

    /** Observation only: the application still creates and owns every real pool, factory, analyzer and consumer. */
    private class Resources : BeanPostProcessor {
        val pools: MutableList<HikariDataSource> = ArrayList()
        val factories: MutableList<EntityManagerFactory> = ArrayList()
        val animalAnalyzers: MutableList<KoreanSearchAnalyzer> = ArrayList()
        val storeAnalyzers: MutableList<KoreanSearchTerms> = ArrayList()
        val borrowed = AtomicInteger()
        val timedOut = AtomicInteger()

        override fun postProcessAfterInitialization(bean: Any, name: String): Any {
            if (bean is HikariDataSource) {
                pools.add(bean)
                bean.metricsTrackerFactory = MetricsTrackerFactory { _, _ ->
                    object : IMetricsTracker {
                        override fun recordConnectionAcquiredNanos(elapsedAcquiredNanos: Long) {
                            borrowed.incrementAndGet()
                        }

                        override fun recordConnectionTimeout() {
                            timedOut.incrementAndGet()
                        }
                    }
                }
            }
            if (bean is EntityManagerFactory) {
                factories.add(bean)
            }
            if (bean is KoreanSearchAnalyzer) {
                animalAnalyzers.add(bean)
            }
            if (bean is KoreanSearchTerms) {
                storeAnalyzers.add(bean)
            }
            return bean
        }
    }
}
